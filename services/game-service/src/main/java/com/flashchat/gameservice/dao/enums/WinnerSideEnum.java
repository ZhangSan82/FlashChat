package com.flashchat.gameservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 胜利方枚举
 * <p>
 * 对应 t_game_room.winner_side 字段。
 */
@Getter
@AllArgsConstructor
public enum WinnerSideEnum {

    /** 平民胜利：所有卧底被淘汰 */
    CIVILIAN("CIVILIAN", "平民胜利"),

    /** 卧底胜利：卧底存活人数 >= 平民存活人数 */
    SPY("SPY", "卧底胜利");

    private final String code;
    private final String desc;

    public static WinnerSideEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (WinnerSideEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}