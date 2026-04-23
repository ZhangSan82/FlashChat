package com.flashchat.cache.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.concurrent.TimeUnit;

/**
 * 缓存配置（Redis + 本地缓存 + 熔断器）。
 * <p>
 * 统一挂在 flashchat.cache 下，确保所有服务使用同一套缓存参数命名空间。
 * <p>
 * 当前主要包含三类配置：
 * 1. Redis 基础 TTL 与分布式锁参数
 * 2. 本地缓存（Caffeine）按业务域的容量 / TTL 配置
 * 3. Redis 熔断器阈值与冷却时间
 */
@Data
@Validated
@ConfigurationProperties(prefix = "flashchat.cache")
public class RedisDistributedProperties {

    /**
     * 配置前缀常量，便于其他地方复用。
     */
    public static final String PREFIX = "flashchat.cache";

    /**
     * Redis 基础配置。
     */
    @Valid
    private RedisProperties redis = new RedisProperties();

    /**
     * safeGet 获取分布式锁的最大等待时间（ms）。
     * 建议与连接池等待时间同一量级，避免业务线程长时间阻塞在锁竞争上。
     */
    private long lockWaitTime = 3000L;

    /**
     * safeGet 持有分布式锁的最大时间（ms）。
     * 必须大于 lockWaitTime + 数据源最坏查询时间，避免锁尚未执行完就自动过期。
     */
    private long lockLeaseTime = 8000L;

    /**
     * 是否开启 TTL 随机化，用于防止大量 key 同时过期造成雪崩。
     */
    private boolean timeoutRandomEnabled = true;

    /**
     * TTL 随机化浮动比例。
     * 例如 0.1 表示在原始 TTL 基础上做 ±10% 的随机抖动。
     */
    private double timeoutRandomRatio = 0.1;

    /**
     * 本地缓存配置。
     */
    @Valid
    private LocalCacheProperties local = new LocalCacheProperties();

    /**
     * Redis 熔断器配置。
     */
    @Valid
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

    /**
     * Redis 基础配置。
     */
    @Data
    public static class RedisProperties {

        /**
         * Redis 默认 TTL。
         */
        private Long valueTimeout = 30000L;

        /**
         * Redis 默认 TTL 单位。
         */
        private TimeUnit valueTimeUnit = TimeUnit.MILLISECONDS;
    }

    /**
     * 本地缓存配置。
     * <p>
     * 这里按业务域拆分配置，而不是做一个统一大缓存，原因是不同域的数据特点不同：
     * - Room        变更少，可适当长 TTL
     * - RoomMember  变更更频繁，应缩短 TTL
     * - Account     查询频繁但改动相对少，TTL 可略长
     */
    @Data
    public static class LocalCacheProperties {

        /**
         * 本地缓存总开关。
         * false 时 LocalCacheManager 会退化为空操作，所有请求直接走 Redis 层。
         */
        private boolean enabled = true;

        /**
         * NULL_VALUE 在本地缓存中的 TTL（秒）。
         * 一般建议短于业务对象 TTL，避免不存在的 key 长时间占用缓存空间。
         */
        private int nullValueTtlSeconds = 15;

        /**
         * Room 域本地缓存。
         */
        private CacheDomainProperties room = CacheDomainProperties.of(10000, 30);

        /**
         * RoomMember 域本地缓存。
         */
        private CacheDomainProperties roomMember = CacheDomainProperties.of(5000, 15);

        /**
         * Account 域本地缓存。
         */
        private CacheDomainProperties account = CacheDomainProperties.of(10000, 45);
    }

    /**
     * 单个业务域的缓存配置。
     */
    @Data
    public static class CacheDomainProperties {

        /**
         * 最大缓存条目数。
         */
        private int maxSize = 5000;

        /**
         * 写入后 TTL（秒）。
         * 对于本地缓存，通常应小于 Redis TTL，避免 Redis 恢复后本地长期持有旧值。
         */
        private int ttlSeconds = 30;

        /**
         * 工厂方法，用于给不同业务域提供差异化默认值。
         */
        public static CacheDomainProperties of(int maxSize, int ttlSeconds) {
            CacheDomainProperties props = new CacheDomainProperties();
            props.setMaxSize(maxSize);
            props.setTtlSeconds(ttlSeconds);
            return props;
        }
    }

    /**
     * Redis 熔断器配置。
     */
    @Data
    public static class CircuitBreakerProperties {

        /**
         * 是否启用熔断器。
         * false 时退化为旧逻辑：每次请求都尝试 Redis，失败后再降级。
         */
        private boolean enabled = true;

        /**
         * 连续失败阈值。
         * 建议值：
         * - 网络稳定、同机房部署：3
         * - 普通场景：5
         * - 对偶发抖动容忍度更高：5~10
         */
        @Min(value = 1, message = "熔断器 failureThreshold 必须 >= 1")
        private int failureThreshold = 5;

        /**
         * 熔断持续时间（ms）。
         * 太短会导致 OPEN / HALF_OPEN 来回抖动；太长则会延迟 Redis 恢复后的重新接入。
         */
        @Min(value = 1000, message = "熔断器 openDurationMs 必须 >= 1000")
        private long openDurationMs = 30000L;
    }
}
