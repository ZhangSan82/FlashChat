package com.flashchat.gameservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 玩家类型枚举
 * 对应 t_game_player.player_type 字段。
 */
@Getter
@AllArgsConstructor
public enum PlayerTypeEnum {

    /** 真人 */
    HUMAN("HUMAN", "真人"),

    /** AI */
    AI("AI", "AI");

    private final String code;
    private final String desc;

    public static PlayerTypeEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (PlayerTypeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
