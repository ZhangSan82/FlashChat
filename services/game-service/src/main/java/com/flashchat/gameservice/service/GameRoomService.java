package com.flashchat.gameservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.gameservice.dao.entity.GameRoomDO;
import com.flashchat.gameservice.dto.req.*;
import com.flashchat.gameservice.dto.resp.GameInfoRespDTO;

/**
 * 游戏房间管理服务
 */
public interface GameRoomService extends IService<GameRoomDO> {

    /**
     * 创建游戏
     */
    GameInfoRespDTO createGame(CreateGameReqDTO request);

    /**
     * 加入游戏
     */
    GameInfoRespDTO joinGame(JoinGameReqDTO request);

    /**
     * 退出游戏（WAITING 阶段）
     */
    void leaveGame(LeaveGameReqDTO request);

    /**
     * 取消游戏（WAITING 阶段，仅创建者）
     */
    void cancelGame(CancelGameReqDTO request);

    /**
     * 添加 AI 玩家（WAITING 阶段，仅创建者）
     */
    GameInfoRespDTO addAiPlayer(AddAiPlayerReqDTO request);

    /**
     * 查询游戏信息
     */
    GameInfoRespDTO getGameInfo(String gameId);
}
