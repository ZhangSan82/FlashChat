package com.flashchat.gameservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游戏历史记录响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameResultRespDTO {

    private String gameId;
    private String roomId;
    private String gameType;

    /** 胜利方：CIVILIAN / SPY，异常结束为 null */
    private String winnerSide;

    /** 结束原因 */
    private String endReason;

    private String civilianWord;
    private String spyWord;

    private LocalDateTime createTime;
    private LocalDateTime endTime;

    /** 全部玩家（含角色、词语、最终状态） */
    private List<PlayerResultDTO> players;

    /** 各轮次记录 */
    private List<RoundResultDTO> rounds;


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayerResultDTO {
        private Long accountId;
        private String nickname;
        private String avatar;
        private String playerType;
        private String role;
        private String word;
        private String finalStatus;
        private Integer playerOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoundResultDTO {
        private Integer roundNumber;

        /** 本轮被淘汰者 accountId，平票为 null */
        private Long eliminatedAccountId;
        private String eliminatedNickname;
        private Boolean isTie;

        /** 本轮发言记录 */
        private List<DescriptionDTO> descriptions;

        /** 本轮投票记录 */
        private List<VoteDTO> votes;
    }

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoteDTO {
        private Long voterAccountId;
        private Long targetAccountId;
        private Boolean isAuto;
    }
}