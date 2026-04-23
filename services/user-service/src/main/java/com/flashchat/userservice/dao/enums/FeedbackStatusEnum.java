package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 反馈处理状态。
 */
@Getter
@AllArgsConstructor
public enum FeedbackStatusEnum {

    NEW("待处理"),
    PROCESSING("处理中"),
    RESOLVED("已解决"),
    CLOSED("已关闭");

    private final String desc;

    public static FeedbackStatusEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (FeedbackStatusEnum value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
