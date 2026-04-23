package com.flashchat.gameservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 调试聊天请求。
 * <p>
 * 仅用于本地联调，方便在没有前端的情况下直接验证
 * 硅基流动 / KIMI 接口是否可用。
 */
@Data
public class AiDebugChatReqDTO {

    /**
     * 系统提示词。
     * <p>
     * 不传时由后端使用默认值。
     */
    private String systemPrompt;

    /**
     * 用户输入内容。
     */
    @NotBlank(message = "prompt 不能为空")
    private String prompt;

    /**
     * 本次请求超时时间，单位秒。
     * <p>
     * 不传则走全局默认配置。
     */
    @Min(value = 1, message = "timeoutSeconds 最小为 1 秒")
    @Max(value = 60, message = "timeoutSeconds 最大为 60 秒")
    private Integer timeoutSeconds;
}
