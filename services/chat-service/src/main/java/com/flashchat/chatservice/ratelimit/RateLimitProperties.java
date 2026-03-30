package com.flashchat.chatservice.ratelimit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息发送限流配置
 */
@Data
@ConfigurationProperties(prefix = "flashchat.rate-limit")
public class RateLimitProperties {

    /**
     * 限流总开关
     * false 时所有限流检查直接放行
     */
    private boolean enabled = true;

    /**
     * 用户全局限流
     */
    private DimensionConfig userGlobal = new DimensionConfig(60_000L, 30);

    /**
     * 用户单房间限流
     */
    private DimensionConfig userRoom = new DimensionConfig(10_000L, 10);

    /**
     * 房间全局限流
     */
    private DimensionConfig roomGlobal = new DimensionConfig(60_000L, 200);

    /**
     * 单个限流维度的配置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionConfig {

        /**
         * 滑动窗口大小（毫秒）
         */
        private long windowMs = 60_000L;

        /**
         * 窗口内最大允许请求数
         */
        private int maxCount = 30;
    }
}
