package com.flashchat.gameservice.ai.model;

import com.flashchat.gameservice.dao.enums.AiPersonaEnum;
import com.flashchat.gameservice.dao.enums.AiProviderEnum;
import com.flashchat.gameservice.dao.enums.GameRoleEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * AI 发言输入快照。
 * <p>
 * 这里只存构建 prompt 必要的只读事实，
 * 提交到 AI 线程池后不再依赖可变的 GameContext。
 */
@Getter
@Builder
@AllArgsConstructor
public class AiDescribeInput {

    private final String gameId;
    private final Long aiPlayerId;
    private final AiProviderEnum provider;
    private final AiPersonaEnum persona;
    private final GameRoleEnum role;
    private final String word;
    private final int roundNumber;
    private final List<SpeechFact> currentRoundDescriptions;
    private final List<SpeechFact> historyDescriptions;

    /**
     * 发言事实。
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class SpeechFact {
        private final int roundNumber;
        private final String speakerNickname;
        private final String content;
        private final boolean skipped;
    }
}
