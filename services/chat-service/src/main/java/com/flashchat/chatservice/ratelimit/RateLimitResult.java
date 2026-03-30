package com.flashchat.chatservice.ratelimit;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 限流检查结果
 */
@Getter
@AllArgsConstructor
public enum RateLimitResult {

    PASS(0, "通过"),
    USER_GLOBAL_EXCEEDED(1, "发送太频繁，请稍后再试"),
    USER_ROOM_EXCEEDED(2, "在该房间发送太频繁，请稍后再试"),
    ROOM_GLOBAL_EXCEEDED(3, "房间消息过多，请稍后再试");

    private final int code;

    /**
     * 面向用户的提示信息
     */
    private final String message;

    public boolean isPassed() {
        return this == PASS;
    }

    /**
     * 根据 Lua 脚本返回值映射为枚举
     */
    public static RateLimitResult of(int luaResult) {
        for (RateLimitResult r : values()) {
            if (r.code == luaResult) {
                return r;
            }
        }
        return null;
    }
}