package com.flashchat.channel;

/**
 * 游戏状态查询服务接口
 * PLAYING 阶段不允许踢出。
 */
public interface GameStateQueryService {

    /**
     * 查询指定用户是否在指定房间的进行中游戏里
     */
    boolean isInPlayingGame(String roomId, Long accountId);
}