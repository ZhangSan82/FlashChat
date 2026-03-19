package com.flashchat.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式缓存配置
 */
@Data
@ConfigurationProperties(prefix = RedisDistributedProperties.PREFIX)
public class RedisDistributedProperties {

    public static final String PREFIX = "flashchat.cache.redis";

    /**
     * 默认超时时间（毫秒）
     */
    private Long valueTimeout = 30000L;

    /**
     * 默认时间单位
     */
    private TimeUnit valueTimeUnit = TimeUnit.MILLISECONDS;
}
