package com.flashchat.gameservice.dao.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 玩家状态枚举
 * 对应 t_game_player.status 字段。

 */
@Getter
@AllArgsConstructor
public enum PlayerStatusEnum {

    /** 存活：正常参与游戏 */
    ALIVE("ALIVE", "存活"),

    /** 已淘汰：被投票出局或被踢出 */
    ELIMINATED("ELIMINATED", "已淘汰"),

    /** 掉线：WebSocket 断开，超过重连窗口后进入托管模式 */
    DISCONNECTED("DISCONNECTED", "掉线");

    private final String code;
    private final String desc;

    public static PlayerStatusEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (PlayerStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 是否仍然"在游戏中"（参与人数计算和胜负判定）
     * ALIVE 和 DISCONNECTED 都算在游戏中，只有 ELIMINATED 才退出。
     */
    public boolean isInGame() {
        return this == ALIVE || this == DISCONNECTED;
    }

    /**
     * 是否可以主动发言
     * <p>
     * 只有 ALIVE 状态可以发言，DISCONNECTED 自动跳过。
     */
    public boolean canSpeak() {
        return this == ALIVE;
    }
}
