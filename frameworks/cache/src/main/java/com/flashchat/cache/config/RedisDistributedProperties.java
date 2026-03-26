package com.flashchat.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.concurrent.TimeUnit;

/**
 * 缓存配置（Redis + 本地缓存）
 */
@Data
@ConfigurationProperties(prefix = RedisDistributedProperties.PREFIX)
public class RedisDistributedProperties {

    public static final String PREFIX = "flashchat.cache.redis";
    /**
     * Redis 配置
     */
    private RedisProperties redis = new RedisProperties();

    // ====================分布式锁超时配置 ====================

    /**
     * safeGet 获取分布式锁的最大等待时间（ms）
     * 建议与 HikariPool connectionTimeout 保持同一量级
     */
    private long lockWaitTime = 3000L;

    /**
     * safeGet 持有分布式锁的最大时间（ms），超时自动释放防止死锁
     * 必须大于 lockWaitTime + 数据源最坏查询耗时
     */
    private long lockLeaseTime = 8000L;

// ==================== 防雪崩配置 ====================

    /**
     * 是否开启过期时间随机化（防雪崩）
     */
    private boolean timeoutRandomEnabled = true;

    /**
     * 过期时间随机化浮动比例
     * 0.1 表示 ±10%，如 timeout=60s → 实际 TTL 范围 [54s, 66s]
     */
    private double timeoutRandomRatio = 0.1;

    /**
     * 本地缓存配置
     */
    private LocalCacheProperties local = new LocalCacheProperties();

    // ==================== Redis 配置 ====================

    @Data
    public static class RedisProperties {

        /** 默认超时时间（毫秒） */
        private Long valueTimeout = 30000L;

        /** 默认时间单位 */
        private TimeUnit valueTimeUnit = TimeUnit.MILLISECONDS;
    }

    // ==================== 本地缓存配置 ====================

    @Data
    public static class LocalCacheProperties {

        /**
         * 本地缓存总开关
         * false 时所有本地缓存操作退化为空操作，直接走 Redis
         */
        private boolean enabled = true;

        /**
         * 空值标记在本地缓存中的 TTL（秒）
         * 通过 Caffeine Expiry API 实现 per-entry TTL
         * 建议小于域级别 TTL，防止"确认不存在的 key"长时间占用缓存
         */
        private int nullValueTtlSeconds = 15;

        /**
         * Room 缓存配置
         * 数据特征：变更频率低，创建后基本不变，状态变更时主动 evict
         */
        private CacheDomainProperties room = CacheDomainProperties.of(10000, 30);

        /**
         * RoomMember 缓存配置
         * 数据特征：变更频率中等，禁言/角色变更时主动 evict
         */
        private CacheDomainProperties roomMember = CacheDomainProperties.of(5000, 15);

        /**
         * Account 缓存配置
         * 数据特征：变更频率低，昵称修改时主动 evict
         */
        private CacheDomainProperties account = CacheDomainProperties.of(10000, 45);
    }

    /**
     * 单个业务域的缓存配置
     */
    @Data
    public static class CacheDomainProperties {

        /** 最大缓存条目数 */
        private int maxSize = 5000;

        /** 写入后过期时间（秒），必须小于 Redis TTL */
        private int ttlSeconds = 30;

        /**
         * 工厂方法，用于 LocalCacheProperties 设置各域差异化默认值
         */
        public static CacheDomainProperties of(int maxSize, int ttlSeconds) {
            CacheDomainProperties props = new CacheDomainProperties();
            props.setMaxSize(maxSize);
            props.setTtlSeconds(ttlSeconds);
            return props;
        }
    }

}
