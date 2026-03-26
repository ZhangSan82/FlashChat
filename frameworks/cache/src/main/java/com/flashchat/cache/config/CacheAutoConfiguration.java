package com.flashchat.cache.config;

import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.cache.StringRedisTemplateProxy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
/**
 * 缓存组件自动装配
 * <p>
 * 注册两个核心 Bean：
 * 1. LocalCacheManager — 本地缓存管理器（Caffeine）
 * 2. MultistageCacheProxy — 多级缓存代理（业务层通过 MultistageCache 接口注入）
 * <p>
 * StringRedisTemplateProxy 不注册为独立 Bean，作为 MultistageCacheProxy 的内部组件
 */
@Slf4j
@AllArgsConstructor
@EnableConfigurationProperties({RedisDistributedProperties.class})
public class CacheAutoConfiguration {

    private final RedisDistributedProperties cacheProperties;

    /**
     * 本地缓存管理器
     */
    @Bean
    public LocalCacheManager localCacheManager(MeterRegistry meterRegistry) {
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
     * 多级缓存代理 — 业务层通过 MultistageCache 接口注入
     * <p>
     * StringRedisTemplateProxy 在此方法内部创建，不注册为独立 Bean
     */
    @Bean
    public MultistageCacheProxy multistageCacheProxy(
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            LocalCacheManager localCacheManager) {
        StringRedisTemplateProxy redisProxy = new StringRedisTemplateProxy(
                stringRedisTemplate, cacheProperties, redissonClient);
        return new MultistageCacheProxy(redisProxy, localCacheManager);
    }

    /**
     * 构建 Caffeine 实例
     * 使用 Expiry API 实现 per-entry TTL：正常值用域 TTL，NullValue 用更短的 TTL
     */
    private Cache<String, Object> buildCaffeineCache(
            RedisDistributedProperties.CacheDomainProperties domainProps,
            int nullValueTtlSeconds,
            String metricName,
            MeterRegistry registry) {

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

        CaffeineCacheMetrics.monitor(registry, cache, metricName, Collections.emptyList());

        return cache;
    }
}