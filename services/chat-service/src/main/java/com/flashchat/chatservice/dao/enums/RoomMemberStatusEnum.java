package com.flashchat.chatservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 房间成员状态枚举
 * 对应 t_room_member.status
 */
@Getter
@AllArgsConstructor
public enum RoomMemberStatusEnum {

    ACTIVE(1, "正常"),
    LEFT(2, "主动离开"),
    KICKED(3, "被踢出");

    private final int code;
    private final String desc;
}