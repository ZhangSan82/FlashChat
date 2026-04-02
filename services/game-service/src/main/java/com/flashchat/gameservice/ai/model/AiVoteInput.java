package com.flashchat.gameservice.ai.model;

import com.flashchat.gameservice.dao.enums.AiPersonaEnum;
import com.flashchat.gameservice.dao.enums.AiProviderEnum;
import com.flashchat.gameservice.dao.enums.GameRoleEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * AI 投票输入快照。
 * <p>
 * 候选列表使用连续编号，方便 LLM 稳定返回数字结果。
 */
@Getter
@Builder
@AllArgsConstructor
public class AiVoteInput {

    private final String gameId;
    private final Long aiPlayerId;
    private final AiProviderEnum provider;
    private final AiPersonaEnum persona;
    private final GameRoleEnum role;
    private final String word;
    private final int roundNumber;
    private final List<VoteCandidate> candidates;

    /**
     * 投票候选人。
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class VoteCandidate {
        private final int index;
        private final Long playerId;
        private final String nickname;
        private final List<RoundSpeechFact> descriptionsByRound;
    }

    /**
     * 某候选人在某一轮的发言集合。
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class RoundSpeechFact {
        private final int roundNumber;
        private final List<SpeechFact> speeches;
    }

    /**
     * 候选人单条发言事实。
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class SpeechFact {
        private final String content;
        private final boolean skipped;
    }
}
