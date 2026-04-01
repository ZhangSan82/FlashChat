package com.flashchat.gameservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游戏信息响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameInfoRespDTO {

    /** 游戏业务 ID */
    private String gameId;

    /** 聊天房间 ID */
    private String roomId;

    /** 游戏类型 */
    private String gameType;

    /** 游戏状态 */
    private String gameStatus;

    /** 当前轮次 */
    private Integer currentRound;

    /** 当前玩家数（含 AI） */
    private Integer playerCount;

    /** 最大玩家数 */
    private Integer maxPlayers;

    /** 最小开始人数 */
    private Integer minPlayers;

    /** 创建者 accountId */
    private Long creatorId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 玩家列表 */
    private List<GamePlayerRespDTO> players;
}