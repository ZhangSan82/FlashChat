package com.flashchat.gameservice.dto.resp;

import com.flashchat.gameservice.config.GameConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端展示用游戏配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameConfigRespDTO {

    private Boolean enableBlank;
    private Integer describeTimeout;
    private Integer voteTimeout;
    private Integer maxPlayers;
    private Integer minPlayers;
    private Integer maxAiPlayers;

    public static GameConfigRespDTO from(GameConfig config) {
        GameConfig source = config == null ? new GameConfig() : config;
        return GameConfigRespDTO.builder()
                .enableBlank(source.isEnableBlankOrDefault())
                .describeTimeout(source.getDescribeTimeoutOrDefault())
                .voteTimeout(source.getVoteTimeoutOrDefault())
                .maxPlayers(source.getMaxPlayersOrDefault())
                .minPlayers(source.getMinPlayersOrDefault())
                .maxAiPlayers(source.getMaxAiPlayersOrDefault())
                .build();
    }
}
