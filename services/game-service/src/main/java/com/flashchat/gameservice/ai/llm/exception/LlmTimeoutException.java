package com.flashchat.gameservice.ai.llm.exception;

import com.flashchat.convention.exception.ServiceException;

/**
 * LLM 调用超时异常。
 * <p>
 * 单独区分超时，方便上层 AI 服务决定是否走降级策略。
 */
public class LlmTimeoutException extends ServiceException {

    public LlmTimeoutException(String message) {
        super(message);
    }
}
