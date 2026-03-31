package com.flashchat.gameservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 游戏角色枚举
 * 对应 t_game_player.role 字段。
 */
@Getter
@AllArgsConstructor
public enum GameRoleEnum {

    /** 平民 */
    CIVILIAN("CIVILIAN", "平民"),

    /** 卧底 */
    SPY("SPY", "卧底"),

    /** 白板： */
    BLANK("BLANK", "白板");

    private final String code;
    private final String desc;

    public static GameRoleEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (GameRoleEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
