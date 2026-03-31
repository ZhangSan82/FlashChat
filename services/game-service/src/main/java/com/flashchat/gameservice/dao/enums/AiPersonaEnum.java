package com.flashchat.gameservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 性格枚举
 * <p>
 * 对应 t_game_player.ai_persona 字段。
 */
@Getter
@AllArgsConstructor
public enum AiPersonaEnum {

    /** 谨慎型：描述模糊，投票保守 */
    CAUTIOUS("CAUTIOUS", "谨慎型"),

    /** 激进型：描述大胆，快速指认 */
    AGGRESSIVE("AGGRESSIVE", "激进型"),

    /** 伪装大师：善于混淆视听 */
    MASTER("MASTER", "伪装大师");

    private final String code;
    private final String desc;

    public static AiPersonaEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (AiPersonaEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
