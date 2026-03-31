package com.flashchat.gameservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 游戏结束原因枚举
 * <p>
 * 对应 t_game_room.end_reason 字段。
 */
@Getter
@AllArgsConstructor
public enum EndReasonEnum {

    /** 正常结束：通过投票淘汰达成胜负条件 */
    NORMAL("NORMAL", "正常结束"),

    /** 聊天房间到期：游戏进行中房间过期，强制终止 */
    ROOM_EXPIRED("ROOM_EXPIRED", "房间到期"),

    /** 全员掉线：所有真人玩家断线超时，游戏自动终止 */
    ALL_DISCONNECTED("ALL_DISCONNECTED", "全员掉线"),

    /** 手动取消：创建者在 WAITING 阶段取消游戏 */
    CANCELLED("CANCELLED", "手动取消");

    private final String code;
    private final String desc;

    public static EndReasonEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (EndReasonEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}