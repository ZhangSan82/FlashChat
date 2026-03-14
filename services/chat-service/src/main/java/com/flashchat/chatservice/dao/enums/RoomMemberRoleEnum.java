package com.flashchat.chatservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 房间成员角色枚举
 * 对应 t_room_member.role
 */
@Getter
@AllArgsConstructor
public enum RoomMemberRoleEnum {

    MEMBER(0, "普通成员"),
    HOST(1, "房主");

    private final int code;
    private final String desc;
}