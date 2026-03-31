package com.flashchat.gameservice.dao.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 游戏状态枚举（DB 层）
 * 对应 t_game_room.game_status
 */
@Getter
@AllArgsConstructor
public enum GameStatusEnum {

    /** 等待中：报名阶段，等待房主开始 */
    WAITING("WAITING", "等待中"),

    /** 进行中：游戏已开始，包含描述、投票、判定等所有轮内阶段 */
    PLAYING("PLAYING", "进行中"),

    /** 已结束：游戏正常结束或异常终止 */
    ENDED("ENDED", "已结束");

    /** 存入 DB 的值 */
    private final String code;

    /** 描述 */
    private final String desc;

    /**
     * 根据 code 查找枚举
     */
    public static GameStatusEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (GameStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}