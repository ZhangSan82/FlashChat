package com.flashchat.gameservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 游戏完整状态响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameStateRespDTO {

    /**
     * 游戏基础信息
     */
    private String gameId;
    private String roomId;
    private String gameStatus;
    private String currentPhase;
    private Integer currentRound;

    /** 我的角色（仅自己可见） */
    private String myRole;

    /** 我的词语（仅自己可见） */
    private String myWord;

    /**
     * 我是否已投票
     */
    private Boolean myVoted;

    /**
     * 当前发言者信息
     */
    private Long currentSpeakerAccountId;
    private String currentSpeakerNickname;
    private Integer currentSpeakerOrder;

    /** 当前回合截止时间（毫秒时间戳），用于前端倒计时 */
    private Long turnDeadline;

    /**
     * 玩家列表
     */
    private List<GamePlayerRespDTO> players;

    /**
     * 当前轮次发言记录
     */
    private List<DescriptionDTO> currentRoundDescriptions;

    /**
     * 当前可投票目标列表。
     * <p>
     * 仅在投票阶段返回，重连后前端可直接用它恢复投票面板。
     */
    private List<VoteTargetDTO> votableTargets;

    /**
     * 发言记录（公开信息）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DescriptionDTO {
        private Long speakerAccountId;
        private String speakerNickname;
        private String content;
        private Boolean isSkipped;
    }

    /**
     * 投票目标信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoteTargetDTO {
        private Long accountId;
        private String nickname;
        private String status;
    }
}
