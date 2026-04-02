package com.flashchat.gameservice.service.impl;

import com.flashchat.channel.GameStateQueryService;
import com.flashchat.gameservice.dao.enums.GameStatusEnum;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GameContextManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 游戏状态查询服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameStateQueryServiceImpl implements GameStateQueryService {

    private final GameContextManager gameContextManager;

    @Override
    public boolean isInPlayingGame(String roomId, Long accountId) {
        if (roomId == null || accountId == null) {
            return false;
        }
        GameContext ctx = gameContextManager.getContextByAccountId(accountId);
        if (ctx == null) {
            return false;
        }
        // 三条件：roomId 匹配 + 游戏状态 PLAYING + accountId 在该游戏中
        return ctx.getRoomId().equals(roomId)
                && ctx.getGameStatus().get() == GameStatusEnum.PLAYING;
    }
}
