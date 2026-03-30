package com.flashchat.chatservice.ratelimit;

/**
 * 消息发送限流器
 */
public interface MessageRateLimiter {

    /**
     * 检查发送者在指定房间的发送频率是否超限
     */
    RateLimitResult checkLimit(Long senderId, String roomId);
}