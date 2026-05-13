package com.flashchat.cache.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
 * 3. Redis 熔断器滑动窗口、失败率与慢调用率阈值
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
        private CacheDomainProperties room = CacheDomainProperties.of(10000, 30, 10);

        /**
         * RoomMember 域本地缓存。
         */
        private CacheDomainProperties roomMember = CacheDomainProperties.of(5000, 15, 5);

        /**
         * Account 域本地缓存。
         */
        private CacheDomainProperties account = CacheDomainProperties.of(10000, 45, 30);
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
         * Redis 异常期间写入本地缓存的短 TTL（秒）。
         * 仅用于短时间吸收热点请求，不承担长期一致性职责。
         */
        private int degradedTtlSeconds = 10;

        /**
         * 工厂方法，用于给不同业务域提供差异化默认值。
         */
        public static CacheDomainProperties of(int maxSize, int ttlSeconds, int degradedTtlSeconds) {
            CacheDomainProperties props = new CacheDomainProperties();
            props.setMaxSize(maxSize);
            props.setTtlSeconds(ttlSeconds);
            props.setDegradedTtlSeconds(degradedTtlSeconds);
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
         * 失败率阈值（百分比）。滑动窗口内失败率达到该值后熔断。
         */
        @DecimalMin(value = "1.0", message = "熔断器 failureRateThreshold 必须 >= 1")
        @DecimalMax(value = "100.0", message = "熔断器 failureRateThreshold 必须 <= 100")
        private float failureRateThreshold = 50.0F;

        /**
         * 慢调用率阈值（百分比）。慢但成功的 Redis 调用也会进入故障判断。
         */
        @DecimalMin(value = "1.0", message = "熔断器 slowCallRateThreshold 必须 >= 1")
        @DecimalMax(value = "100.0", message = "熔断器 slowCallRateThreshold 必须 <= 100")
        private float slowCallRateThreshold = 50.0F;

        /**
         * 慢调用判定阈值（ms）。Redis 调用耗时超过该值即计为 slow call。
         */
        @Min(value = 1, message = "熔断器 slowCallDurationThresholdMs 必须 >= 1")
        private long slowCallDurationThresholdMs = 100L;

        /**
         * 基于时间的滑动窗口大小（秒）。
         */
        @Min(value = 1, message = "熔断器 slidingWindowSizeSeconds 必须 >= 1")
        private int slidingWindowSizeSeconds = 10;

        /**
         * 窗口内至少达到该调用数后，才计算失败率和慢调用率。
         */
        @Min(value = 1, message = "熔断器 minimumNumberOfCalls 必须 >= 1")
        private int minimumNumberOfCalls = 20;

        /**
         * HALF_OPEN 状态下允许通过的试探请求数。
         */
        @Min(value = 1, message = "熔断器 permittedNumberOfCallsInHalfOpenState 必须 >= 1")
        private int permittedNumberOfCallsInHalfOpenState = 3;

        /**
         * OPEN 状态保持时间（ms）。到期后进入 HALF_OPEN 试探恢复。
         */
        @Min(value = 1000, message = "熔断器 waitDurationInOpenStateMs 必须 >= 1000")
        private long waitDurationInOpenStateMs = 5000L;
    }
}
