package com.flashchat.gameservice.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashchat.channel.ChannelPushService;
import com.flashchat.channel.event.MemberKickedFromRoomEvent;
import com.flashchat.channel.event.MemberOfflineEvent;
import com.flashchat.channel.event.MemberOnlineEvent;
import com.flashchat.convention.storage.OssAssetUrlService;
import com.flashchat.gameservice.constant.GameWsEventType;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import com.flashchat.gameservice.dao.enums.EndReasonEnum;
import com.flashchat.gameservice.dao.enums.GameStatusEnum;
import com.flashchat.gameservice.dao.enums.PlayerStatusEnum;
import com.flashchat.gameservice.engine.GameActionLockManager;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GameContextManager;
import com.flashchat.gameservice.engine.GamePlayerInfo;
import com.flashchat.gameservice.engine.WhoIsSpyEngine;
import com.flashchat.gameservice.service.GamePlayerService;
import com.flashchat.gameservice.timer.GameTurnTimer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 聊天模块 -> 游戏模块事件监听器。
 * <p>
 * 负责把 chat-service 发布的房间侧事件同步到游戏状态：
 * 1. 成员掉线 -> 标记 DISCONNECTED
 * 2. 成员重连 -> 恢复 ALIVE
 * 3. WAITING 阶段成员被踢 -> 从游戏报名列表移除
 * <p>
 * 规则遵循之前已经确认的结论：
 * 1. PLAYING 阶段不允许踢人，因此收到踢人事件只记录 warn，不做淘汰
 * 2. offline / online 只影响进行中的游戏
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventListener {

    /**
     * 掉线重连宽限期。
     * <p>
     * 当前实现中玩家一旦掉线就会立即标记 DISCONNECTED，
     * 30 秒定时器主要用于托管提示和后续扩展。
     */
    private static final int DISCONNECT_GRACE_SECONDS = 30;

    private final GameContextManager gameContextManager;
    private final GameActionLockManager gameActionLockManager;
    private final GamePlayerService gamePlayerService;
    private final GameTurnTimer gameTurnTimer;
    private final ChannelPushService channelPushService;
    private final WhoIsSpyEngine whoIsSpyEngine;
    private final OssAssetUrlService ossAssetUrlService;

    /**
     * 成员掉线。
     * <p>
     * 异步处理，避免拖慢 chat-service 的 WS 下线清理流程。
     */
    @Async
    @EventListener
    public void onMemberOffline(MemberOfflineEvent event) {
        Long accountId = event.getAccountId();
        if (accountId == null) {
            return;
        }

        GameContext ctx = gameContextManager.getContextByAccountId(accountId);
        if (ctx == null) {
            return;
        }

        try (GameActionLockManager.LockHandle ignored =
                     gameActionLockManager.lock("game:" + ctx.getGameId())) {
            if (ctx.getGameStatus().get() != GameStatusEnum.PLAYING) {
                return;
            }

            GamePlayerInfo player = ctx.getPlayerByAccountId(accountId);
            if (player == null || !player.isHuman()) {
                return;
            }
            if (player.getStatus() == PlayerStatusEnum.ELIMINATED
                    || player.getStatus() == PlayerStatusEnum.DISCONNECTED) {
                return;
            }

            player.setStatus(PlayerStatusEnum.DISCONNECTED);
            broadcastPlayerDisconnected(ctx, player, player.getNickname() + " 已掉线，30 秒内可重连");
            scheduleDisconnectGrace(ctx, player);
            whoIsSpyEngine.handlePlayerDisconnected(ctx, player);

            if (ctx.getGameStatus().get() != GameStatusEnum.PLAYING) {
                return;
            }

            // 所有真人都已不在 ALIVE 状态时，直接异常结束
            if (allHumansDisconnected(ctx)) {
                whoIsSpyEngine.endGame(ctx, null, EndReasonEnum.ALL_DISCONNECTED);
                return;
            }

            log.info("[GameEvent] 成员掉线已同步到游戏 gameId={}, accountId={}",
                    ctx.getGameId(), accountId);
        }
    }

    /**
     * 成员重连。
     * <p>
     * 异步处理，避免拖慢 chat-service 的 WS 上线恢复流程。
     */
    @Async
    @EventListener
    public void onMemberOnline(MemberOnlineEvent event) {
        Long accountId = event.getAccountId();
        if (accountId == null) {
            return;
        }

        GameContext ctx = gameContextManager.getContextByAccountId(accountId);
        if (ctx == null) {
            return;
        }

        try (GameActionLockManager.LockHandle ignored =
                     gameActionLockManager.lock("game:" + ctx.getGameId())) {
            if (ctx.getGameStatus().get() != GameStatusEnum.PLAYING) {
                return;
            }

            GamePlayerInfo player = ctx.getPlayerByAccountId(accountId);
            if (player == null || !player.isHuman()) {
                return;
            }
            if (player.getStatus() != PlayerStatusEnum.DISCONNECTED) {
                return;
            }

            gameTurnTimer.cancelDisconnectTimer(ctx, accountId);
            player.setStatus(PlayerStatusEnum.ALIVE);
            broadcastPlayerReconnected(ctx, player);

            log.info("[GameEvent] 成员重连已同步到游戏 gameId={}, accountId={}",
                    ctx.getGameId(), accountId);
        }
    }

    /**
     * 成员被踢出房间。
     * <p>
     * 只处理 WAITING 阶段的报名清理。
     * PLAYING 阶段按既定规则只记录日志，不做淘汰。
     */
    @EventListener
    public void onMemberKicked(MemberKickedFromRoomEvent event) {
        Long accountId = event.getAccountId();
        String roomId = event.getRoomId();
        if (accountId == null || roomId == null || roomId.isBlank()) {
            return;
        }

        GameContext ctx = gameContextManager.getContextByAccountId(accountId);
        if (ctx == null || !Objects.equals(ctx.getRoomId(), roomId)) {
            return;
        }

        try (GameActionLockManager.LockHandle ignored =
                     gameActionLockManager.lock("game:" + ctx.getGameId())) {
            GameStatusEnum status = ctx.getGameStatus().get();
            if (status == GameStatusEnum.PLAYING) {
                log.warn("[GameEvent] 收到 PLAYING 阶段踢人事件但规则不允许处理, gameId={}, accountId={}, roomId={}",
                        ctx.getGameId(), accountId, roomId);
                return;
            }
            if (status != GameStatusEnum.WAITING) {
                return;
            }

            GamePlayerDO playerRecord = getGamePlayer(ctx.getGameId(), accountId);
            boolean removed = gamePlayerService.remove(new LambdaQueryWrapper<GamePlayerDO>()
                    .eq(GamePlayerDO::getGameId, ctx.getGameId())
                    .eq(GamePlayerDO::getAccountId, accountId));

            // 即便 DB 里已经不存在，也要尽量清理内存态，保证反向索引不会残留
            ctx.removePlayer(accountId);
            gameContextManager.unbindPlayer(accountId, ctx.getGameId());

            if (!removed && playerRecord == null) {
                return;
            }

            List<Long> broadcastTargets = ctx.getPlayerAccountIds().stream()
                    .filter(id -> id > 0)
                    .toList();
            channelPushService.sendToUsers(
                    broadcastTargets,
                    GameWsEventType.GAME_PLAYER_LEFT,
                    ctx.getRoomId(),
                    buildPlayerChangedPayload(ctx.getGameId(), playerRecord, accountId, ctx.getPlayerAccountIds().size())
            );

            log.info("[GameEvent] WAITING 阶段踢人已同步到游戏 gameId={}, accountId={}, operatorId={}",
                    ctx.getGameId(), accountId, event.getOperatorId());
        }
    }

    /**
     * 掉线宽限期定时任务。
     * <p>
     * 超时后玩家仍保持 DISCONNECTED，只补一条“进入托管”通知，
     * 方便前端区分“刚掉线”和“已进入托管”。
     */
    private void scheduleDisconnectGrace(GameContext ctx, GamePlayerInfo player) {
        gameTurnTimer.scheduleDisconnectTimeout(ctx, player.getAccountId(), DISCONNECT_GRACE_SECONDS, () -> {
            try (GameActionLockManager.LockHandle ignored =
                         gameActionLockManager.lock("game:" + ctx.getGameId())) {
                if (ctx.getGameStatus().get() != GameStatusEnum.PLAYING) {
                    return;
                }
                GamePlayerInfo latest = ctx.getPlayerByAccountId(player.getAccountId());
                if (latest == null || latest.getStatus() != PlayerStatusEnum.DISCONNECTED) {
                    return;
                }
                broadcastPlayerDisconnected(ctx, latest, latest.getNickname() + " 超时未重连，已进入托管");
            }
        });
    }

    private void broadcastPlayerDisconnected(GameContext ctx, GamePlayerInfo player, String message) {
        Map<String, Object> data = new LinkedHashMap<>(5);
        data.put("accountId", player.getAccountId());
        data.put("nickname", player.getNickname());
        data.put("status", PlayerStatusEnum.DISCONNECTED.getCode());
        data.put("message", message);
        data.put("isCurrentSpeaker", false);
        channelPushService.sendToUsers(ctx.getPlayerAccountIds(),
                GameWsEventType.GAME_PLAYER_DISCONNECTED, ctx.getRoomId(), data);
    }

    private void broadcastPlayerReconnected(GameContext ctx, GamePlayerInfo player) {
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("accountId", player.getAccountId());
        data.put("nickname", player.getNickname());
        data.put("status", PlayerStatusEnum.ALIVE.getCode());
        data.put("message", player.getNickname() + " 已重连");
        channelPushService.sendToUsers(ctx.getPlayerAccountIds(),
                GameWsEventType.GAME_PLAYER_RECONNECTED, ctx.getRoomId(), data);
    }

    private Map<String, Object> buildPlayerChangedPayload(String gameId,
                                                                    GamePlayerDO playerRecord,
                                                                    Long accountId,
                                                                    int playerCount) {
        Map<String, Object> payload = new LinkedHashMap<>(6);
        payload.put("gameId", gameId);
        payload.put("accountId", accountId);
        payload.put("nickname", playerRecord != null ? playerRecord.getNickname() : "");
        payload.put("avatar", playerRecord != null ? ossAssetUrlService.resolveAccessUrl(playerRecord.getAvatar()) : "");
        payload.put("playerType", playerRecord != null ? playerRecord.getPlayerType() : null);
        payload.put("playerCount", playerCount);
        return payload;
    }

    private boolean allHumansDisconnected(GameContext ctx) {
        return ctx.getAlivePlayers().stream()
                .filter(GamePlayerInfo::isHuman)
                .noneMatch(player -> player.getStatus() == PlayerStatusEnum.ALIVE);
    }

    private GamePlayerDO getGamePlayer(String gameId, Long accountId) {
        return gamePlayerService.lambdaQuery()
                .select(GamePlayerDO::getId, GamePlayerDO::getGameId, GamePlayerDO::getAccountId,
                        GamePlayerDO::getPlayerType, GamePlayerDO::getNickname, GamePlayerDO::getAvatar)
                .eq(GamePlayerDO::getGameId, gameId)
                .eq(GamePlayerDO::getAccountId, accountId)
                .last("LIMIT 1")
                .one();
    }
}
