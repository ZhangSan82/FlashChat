package com.flashchat.chatservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RoomDurationEnum {

    MIN_10(10, "10 分钟", 0),
    MIN_30(30, "30 分钟", 10),
    HOUR_1(60, "1 小时", 20),
    HOUR_2(120, "2 小时", 30),
    HOUR_6(360, "6 小时", 50),
    HOUR_12(720, "12 小时", 80),
    HOUR_24(1440, "24 小时", 100),
    DAY_3(4320, "3 天", 200),
    DAY_7(10080, "7 天", 400);

    /** 时长（分钟） */
    private final int minutes;

    /** 文案 */
    private final String desc;

    /**消耗积分*/
    private final int cost;

    public static RoomDurationEnum of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}