package com.flashchat.gameservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 厂商枚举
 * <p>
 * 对应 t_game_player.ai_provider 字段。
 */
@Getter
@AllArgsConstructor
public enum AiProviderEnum {

    /** DeepSeek */
    DEEPSEEK("DEEPSEEK", "DeepSeek"),

    /** kimi */
    OPENAI("KIMI", "KIMI"),

    /** 千问  */
    QWEN("QWEN", "通义千问");

    private final String code;
    private final String desc;

    public static AiProviderEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (AiProviderEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
