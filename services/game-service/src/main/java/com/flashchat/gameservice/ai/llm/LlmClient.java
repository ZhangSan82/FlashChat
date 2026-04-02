package com.flashchat.gameservice.ai.llm;

import com.flashchat.gameservice.dao.enums.AiProviderEnum;

import java.time.Duration;
import java.util.List;

/**
 * LLM 客户端统一抽象。
 */
public interface LlmClient {

    /**
     * 当前客户端对应的 AI 厂商。
     */
    AiProviderEnum getProvider();

    /**
     * 同步调用大模型生成文本。
     *
     * @param messages 对话消息列表
     * @param timeout 本次调用允许的最大耗时
     * @return 模型返回的文本内容
     */
    String chat(List<LlmMessage> messages, Duration timeout);
}
