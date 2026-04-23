package com.flashchat.cache.config;

import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.cache.RedisCircuitBreaker;
import com.flashchat.cache.StringRedisTemplateProxy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 缓存组件自动装配。
 * <p>
 * 统一注册三个核心 Bean：
 * 1. LocalCacheManager    本地缓存管理器（Caffeine）
 * 2. RedisCircuitBreaker  Redis 熔断器
 * 3. MultistageCacheProxy 多级缓存代理
 * <p>
 * 设计要点：
 * 1. MeterRegistry 为可选依赖，没有监控系统时缓存框架仍能工作
 * 2. 熔断器关闭时仍返回一个「永远放行」的空操作熔断器，避免业务侧出现 if-else 分叉
 * 3. StringRedisTemplateProxy 不暴露成独立 Bean，只作为框架内部实现细节存在
 */
@Slf4j
@AllArgsConstructor
@EnableConfigurationProperties({RedisDistributedProperties.class})
public class CacheAutoConfiguration {

    /**
     * flashchat.cache 对应的统一配置。
     */
    private final RedisDistributedProperties cacheProperties;

    /**
     * 本地缓存管理器。
     * <p>
     * 根据不同业务域创建独立的 Caffeine cache，并通过 key 前缀自动路由。
     * MeterRegistry 为 null 时仅关闭指标注册，不影响本地缓存功能。
     */
    @Bean
    public LocalCacheManager localCacheManager(@Nullable MeterRegistry meterRegistry) {
        RedisDistributedProperties.LocalCacheProperties localProps = cacheProperties.getLocal();

        if (!localProps.isEnabled()) {
            log.info("[本地缓存] 已关闭（flashchat.cache.local.enabled=false）");
            return new LocalCacheManager(null, null, null, localProps);
        }

        int nullTtl = localProps.getNullValueTtlSeconds();

        Cache<String, Object> roomCache = buildCaffeineCache(
                localProps.getRoom(), nullTtl, "flashchat.local.room", meterRegistry);
        Cache<String, Object> roomMemberCache = buildCaffeineCache(
                localProps.getRoomMember(), nullTtl, "flashchat.local.roomMember", meterRegistry);
        Cache<String, Object> accountCache = buildCaffeineCache(
                localProps.getAccount(), nullTtl, "flashchat.local.account", meterRegistry);

        log.info("[本地缓存] 初始化完成 | room(max={}, ttl={}s) | roomMember(max={}, ttl={}s) | account(max={}, ttl={}s) | nullTtl={}s",
                localProps.getRoom().getMaxSize(), localProps.getRoom().getTtlSeconds(),
                localProps.getRoomMember().getMaxSize(), localProps.getRoomMember().getTtlSeconds(),
                localProps.getAccount().getMaxSize(), localProps.getAccount().getTtlSeconds(),
                nullTtl);

        return new LocalCacheManager(roomCache, roomMemberCache, accountCache, localProps);
    }

    /**
     * Redis 熔断器。
     * <p>
     * 关闭熔断时返回一个 failureThreshold/openDuration 极大的空操作熔断器，
     * 这样 MultistageCacheProxy 无需在业务逻辑里判断「是否启用熔断」。
     */
    @Bean
    public RedisCircuitBreaker redisCircuitBreaker(@Nullable MeterRegistry meterRegistry) {
        RedisDistributedProperties.CircuitBreakerProperties cbProps = cacheProperties.getCircuitBreaker();

        if (!cbProps.isEnabled()) {
            log.info("[熔断器] 已关闭（flashchat.cache.circuit-breaker.enabled=false）");
            return new RedisCircuitBreaker(Integer.MAX_VALUE, Long.MAX_VALUE, null);
        }

        log.info("[熔断器] 初始化完成 | failureThreshold={}, openDuration={}ms",
                cbProps.getFailureThreshold(), cbProps.getOpenDurationMs());
        return new RedisCircuitBreaker(
                cbProps.getFailureThreshold(),
                cbProps.getOpenDurationMs(),
                meterRegistry
        );
    }

    /**
     * 多级缓存代理。
     * <p>
     * 业务层最终依赖的是这一层，而不是直接操作 StringRedisTemplate / Redisson。
     * 这样可以把本地缓存、熔断、待修复补偿等能力统一封装在框架内部。
     */
    @Bean
    public MultistageCacheProxy multistageCacheProxy(
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            LocalCacheManager localCacheManager,
            RedisCircuitBreaker redisCircuitBreaker,
            @Nullable MeterRegistry meterRegistry) {
        StringRedisTemplateProxy redisProxy = new StringRedisTemplateProxy(
                stringRedisTemplate, cacheProperties, redissonClient);
        return new MultistageCacheProxy(
                redisProxy,
                localCacheManager,
                redisCircuitBreaker,
                meterRegistry
        );
    }

    /**
     * 构建单个业务域的 Caffeine 实例。
     * <p>
     * 这里使用 Expiry API 支持 per-entry TTL：
     * - 正常值使用域级 TTL
     * - NULL_VALUE 使用更短 TTL，避免「确认不存在的 key」长时间占用本地缓存
     */
    private Cache<String, Object> buildCaffeineCache(
            RedisDistributedProperties.CacheDomainProperties domainProps,
            int nullValueTtlSeconds,
            String metricName,
            @Nullable MeterRegistry registry) {

        long domainTtlNanos = TimeUnit.SECONDS.toNanos(domainProps.getTtlSeconds());
        long nullTtlNanos = TimeUnit.SECONDS.toNanos(nullValueTtlSeconds);

        Cache<String, Object> cache = Caffeine.newBuilder()
                .maximumSize(domainProps.getMaxSize())
                .expireAfter(new Expiry<String, Object>() {
                    @Override
                    public long expireAfterCreate(String key, Object value, long currentTime) {
                        return LocalCacheManager.isNullValue(value) ? nullTtlNanos : domainTtlNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, Object value,
                                                  long currentTime, long currentDuration) {
                        return LocalCacheManager.isNullValue(value) ? nullTtlNanos : domainTtlNanos;
                    }

                    @Override
                    public long expireAfterRead(String key, Object value,
                                                long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .recordStats()
                .build();

        // 监控系统可选，框架层不应因缺少 MeterRegistry 而失败。
        if (registry != null) {
            CaffeineCacheMetrics.monitor(registry, cache, metricName, Collections.emptyList());
        }

        return cache;
    }
}
