package com.flashchat.gameservice.ai.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 对话消息。
 * <p>
 * 统一封装 system / user / assistant 三种角色，
 * 方便不同 provider 复用同一套 prompt 结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {

    private String role;

    private String content;

    public static LlmMessage system(String content) {
        return LlmMessage.builder()
                .role("system")
                .content(content)
                .build();
    }

    public static LlmMessage user(String content) {
        return LlmMessage.builder()
                .role("user")
                .content(content)
                .build();
    }

    public static LlmMessage assistant(String content) {
        return LlmMessage.builder()
                .role("assistant")
                .content(content)
                .build();
    }
}
