package com.flashchat.gameservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 添加 AI 玩家请求
 */
@Data
public class AddAiPlayerReqDTO {

    @NotBlank(message = "游戏 ID 不能为空")
    private String gameId;

    /**
     * AI 厂商
     */
    private String provider;

    /**
     * AI 性格
     */
    private String persona;
}