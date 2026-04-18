package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 管理员操作对象类型。
 */
@Getter
@AllArgsConstructor
public enum AdminOperationTargetTypeEnum {

    ACCOUNT("账号"),
    ROOM("房间"),
    ROOM_MEMBER("房间成员");

    private final String desc;
}
