package com.flashchat.gameservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import com.flashchat.gameservice.dao.entity.GameRoomDO;
import com.flashchat.gameservice.dto.req.StartGameReqDTO;
import com.flashchat.gameservice.dto.req.SubmitDescriptionReqDTO;
import com.flashchat.gameservice.dto.req.SubmitVoteReqDTO;
import com.flashchat.gameservice.dto.resp.GameResultRespDTO;
import com.flashchat.gameservice.dto.resp.GameStateRespDTO;

/**
 * 游戏进行服务
 */
public interface GamePlayService extends  IService<GamePlayerDO> {

    /**
     * 开始游戏（仅创建者）
     */
    void startGame(StartGameReqDTO request);

    /**
     * 提交发言
     */
    void submitDescription(SubmitDescriptionReqDTO request);

    /**
     * 提交投票
     */
    void submitVote(SubmitVoteReqDTO request);

    /**
     * 查询游戏实时状态（支持断线重连恢复）
     */
    GameStateRespDTO getGameState(String gameId);

    /**
     * 查询已结束游戏的完整记录
     */
    GameResultRespDTO getGameHistory(String gameId);
}
