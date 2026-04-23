package com.flashchat.gameservice.dto.resp;

import lombok.Builder;
import lombok.Data;

/**
 * AI 调试聊天响应。
 */
@Data
@Builder
public class AiDebugChatRespDTO {

    /**
     * 调用的 AI 厂商。
     */
    private String provider;

    /**
     * 本次实际使用的系统提示词。
     */
    private String systemPrompt;

    /**
     * 用户输入。
     */
    private String prompt;

    /**
     * 模型返回内容。
     */
    private String answer;
}
