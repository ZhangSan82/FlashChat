package com.flashchat.chatservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 房间状态枚举
 * 对应 t_room.status
 */
@Getter
@AllArgsConstructor
public enum RoomStatusEnum {

    WAITING(0, "等待中"),
    ACTIVE(1, "活跃"),
    EXPIRING(2, "即将到期"),
    GRACE(3, "宽限期"),
    CLOSED(4, "已关闭");

    private final int code;
    private final String desc;

    public static RoomStatusEnum of(int code) {
        for (RoomStatusEnum v : values()) {
            if (v.code == code) return v;
        }
        return null;
    }

    /**
     * 是否允许新成员加入
     */
    public boolean canJoin() {
        return this == WAITING || this == ACTIVE || this == EXPIRING;
    }

    /**
     * 是否允许发送消息
     */
    public boolean canSendMsg() {
        return this == ACTIVE || this == EXPIRING;
    }
}