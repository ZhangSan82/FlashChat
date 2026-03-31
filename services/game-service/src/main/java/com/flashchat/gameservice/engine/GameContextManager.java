package com.flashchat.gameservice.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏上下文全局管理器
 * <p>
 * 维护所有进行中的游戏上下文 + accountId → gameId 的反向索引。
 * <p>
 * 反向索引用途：玩家掉线/重连时，O(1) 查到该玩家在哪局游戏里，
 * 不需要遍历所有 GameContext。
 */
@Slf4j
@Component
public class GameContextManager {

    /** gameId → GameContext */
    private final ConcurrentHashMap<String, GameContext> games = new ConcurrentHashMap<>();

    /** accountId → gameId（反向索引，一个玩家同时只在一局游戏中） */
    private final ConcurrentHashMap<Long, String> accountToGameId = new ConcurrentHashMap<>();

    // ==================== 生命周期 ====================

    /**
     * 注册游戏上下文（创建游戏时）
     */
    public void register(GameContext context) {
        games.put(context.getGameId(), context);
        log.info("[GameContext 注册] gameId={}, roomId={}", context.getGameId(), context.getRoomId());
    }

    /**
     * 移除游戏上下文（游戏结束时）
     * <p>
     * 同时清理该游戏所有玩家的反向索引。
     */
    public void remove(String gameId) {
        GameContext context = games.remove(gameId);
        if (context != null) {
            for (Long accountId : context.getPlayerAccountIds()) {
                accountToGameId.remove(accountId, gameId);
            }
            log.info("[GameContext 移除] gameId={}", gameId);
        }
    }

    // ==================== 玩家索引管理 ====================

    /**
     * 玩家加入游戏时注册反向索引
     */
    public void bindPlayer(Long accountId, String gameId) {
        accountToGameId.put(accountId, gameId);
    }

    /**
     * 玩家退出游戏时移除反向索引（WAITING 阶段退出）
     */
    public void unbindPlayer(Long accountId, String gameId) {
        accountToGameId.remove(accountId, gameId);
    }

    // ==================== 查询 ====================

    /**
     * 通过 gameId 获取上下文
     */
    public GameContext getContext(String gameId) {
        return games.get(gameId);
    }

    /**
     * 通过 accountId 查找该玩家所在的游戏
     * <p>
     * O(1) 查询，供掉线/重连事件处理使用。
     *
     * @return GameContext，未在任何游戏中返回 null
     */
    public GameContext getContextByAccountId(Long accountId) {
        String gameId = accountToGameId.get(accountId);
        if (gameId == null) {
            return null;
        }
        return games.get(gameId);
    }

    /**
     * 通过 roomId 查找该房间当前进行中的游戏
     * <p>
     * 遍历查找，但游戏数量通常很少（< 100），性能可接受。
     *
     * @return GameContext，没有进行中的游戏返回 null
     */
    public GameContext getActiveContextByRoomId(String roomId) {
        for (GameContext ctx : games.values()) {
            if (ctx.getRoomId().equals(roomId)) {
                return ctx;
            }
        }
        return null;
    }

    /**
     * 获取当前进行中的游戏数量（监控用）
     */
    public int getActiveGameCount() {
        return games.size();
    }
}
