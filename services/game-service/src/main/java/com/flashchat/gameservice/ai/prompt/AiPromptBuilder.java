package com.flashchat.gameservice.ai.prompt;

import com.flashchat.gameservice.ai.llm.LlmMessage;
import com.flashchat.gameservice.ai.model.AiDescribeInput;
import com.flashchat.gameservice.ai.model.AiVoteInput;

import java.util.List;

/**
 * AI Prompt 构建器。
 */
public interface AiPromptBuilder {

    /**
     * 构建 AI 发言 prompt。
     */
    List<LlmMessage> buildDescribePrompt(AiDescribeInput input);

    /**
     * 构建 AI 投票 prompt。
     */
    List<LlmMessage> buildVotePrompt(AiVoteInput input);
}
