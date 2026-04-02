package com.flashchat.gameservice.service;

import com.flashchat.gameservice.dao.entity.WordPairDO;
import com.flashchat.gameservice.dao.enums.EndReasonEnum;
import com.flashchat.gameservice.dao.enums.WinnerSideEnum;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GamePlayerInfo;

import java.util.List;

/**
 * 游戏数据持久化服务
 */
public interface GamePersistService {

    /**
     * 游戏开始时落库
     */
    void persistGameStart(GameContext ctx, List<GamePlayerInfo> players, WordPairDO wordPair);

    /**
     * 每轮结束时落库
     */
    void persistRoundResult(GameContext ctx, Long eliminatedPlayerId, boolean isTie);

    /**
     * 游戏结束时落库
     */
    void persistGameEnd(GameContext ctx, WinnerSideEnum winner, EndReasonEnum reason);
}
