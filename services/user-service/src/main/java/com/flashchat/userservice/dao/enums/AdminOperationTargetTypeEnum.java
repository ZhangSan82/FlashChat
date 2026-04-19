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
    ROOM_MEMBER("房间成员"),
    FEEDBACK("用户反馈");

    private final String desc;

    public static AdminOperationTargetTypeEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (AdminOperationTargetTypeEnum value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
