package com.flashchat.gameservice.timer;

import com.flashchat.gameservice.engine.GameContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 游戏超时管理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameTurnTimer {

    @Qualifier("gameTimerExecutor")
    private final ScheduledExecutorService timerExecutor;

    /**
     * 启动发言超时定时任务
     * <p>
     * 超时后执行 timeoutAction。timeoutAction 内部应做 CAS 检查，
     * 防止与玩家正常提交产生竞争。
     */
    public ScheduledFuture<?> scheduleDescribeTimeout(GameContext context,
                                                      int timeoutSeconds,
                                                      Runnable timeoutAction) {
        // 先取消上一个定时任务
        context.cancelCurrentTurnTimer();

        // 设置截止时间
        context.setTurnDeadline(System.currentTimeMillis() + timeoutSeconds * 1000L);

        // 启动新定时任务
        ScheduledFuture<?> future = timerExecutor.schedule(() -> {
            try {
                timeoutAction.run();
            } catch (Exception e) {
                log.error("[发言超时处理异常] gameId={}", context.getGameId(), e);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        context.setCurrentTurnTimer(future);
        return future;
    }

    /**
     * 启动投票超时定时任务
     */
    public ScheduledFuture<?> scheduleVoteTimeout(GameContext context,
                                                  int timeoutSeconds,
                                                  Runnable timeoutAction) {
        context.cancelCurrentTurnTimer();
        context.setTurnDeadline(System.currentTimeMillis() + timeoutSeconds * 1000L);

        ScheduledFuture<?> future = timerExecutor.schedule(() -> {
            try {
                timeoutAction.run();
            } catch (Exception e) {
                log.error("[投票超时处理异常] gameId={}", context.getGameId(), e);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        context.setCurrentTurnTimer(future);
        return future;
    }

    /**
     * 启动掉线重连定时任务
     */
    public ScheduledFuture<?> scheduleDisconnectTimeout(GameContext context,
                                                        Long accountId,
                                                        int timeoutSeconds,
                                                        Runnable timeoutAction) {
        // 取消该玩家之前的重连定时器（如果有）
        cancelDisconnectTimer(context, accountId);

        ScheduledFuture<?> future = timerExecutor.schedule(() -> {
            try {
                timeoutAction.run();
            } catch (Exception e) {
                log.error("[掉线超时处理异常] gameId={}, accountId={}",
                        context.getGameId(), accountId, e);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        context.getDisconnectTimers().put(accountId, future);
        return future;
    }

    /**
     * 取消掉线重连定时器（玩家重连时调用）
     */
    public void cancelDisconnectTimer(GameContext context, Long accountId) {
        ScheduledFuture<?> existing = context.getDisconnectTimers().remove(accountId);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
            log.debug("[取消重连定时器] gameId={}, accountId={}", context.getGameId(), accountId);
        }
    }

    /**
     * 取消游戏的所有定时任务（游戏结束时调用）
     */
    public void cancelAll(GameContext context) {
        context.cancelCurrentTurnTimer();
        context.getDisconnectTimers().forEach((accountId, future) -> {
            if (!future.isDone()) {
                future.cancel(false);
            }
        });
        context.getDisconnectTimers().clear();
        log.debug("[清理所有定时器] gameId={}", context.getGameId());
    }
}
