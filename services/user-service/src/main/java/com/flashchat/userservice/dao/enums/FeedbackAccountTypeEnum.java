package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 反馈提交人的账号类型。
 */
@Getter
@AllArgsConstructor
public enum FeedbackAccountTypeEnum {

    GUEST("游客/未注册"),
    REGISTERED("已注册用户");

    private final String desc;

    public static FeedbackAccountTypeEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (FeedbackAccountTypeEnum value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
