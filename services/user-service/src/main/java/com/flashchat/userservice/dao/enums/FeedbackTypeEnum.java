package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 反馈类型枚举。
 */
@Getter
@AllArgsConstructor
public enum FeedbackTypeEnum {

    SUGGESTION("功能建议"),
    BUG("Bug 反馈"),
    EXPERIENCE("体验问题"),
    ACCOUNT("账号问题"),
    ROOM("房间问题"),
    OTHER("其他");

    private final String desc;

    public static FeedbackTypeEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (FeedbackTypeEnum value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
