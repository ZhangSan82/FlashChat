package com.flashchat.cache;

import com.alibaba.fastjson2.JSON;
import com.flashchat.cache.config.RedisDistributedProperties;
import com.flashchat.cache.core.CacheGetFilter;
import com.flashchat.cache.core.CacheGetIfAbsent;
import com.flashchat.cache.core.CacheLoader;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.cache.toolkit.FastJson2Util;
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 分布式缓存 - Redis 实现
 * 静态代理模式：在 StringRedisTemplate 基础上增加防穿透/击穿/雪崩能力
 * 底层通过 {@link RedissonClient} 提供分布式锁，{@link StringRedisTemplate} 提供 Redis 操作
 */
@Slf4j
@RequiredArgsConstructor
public class StringRedisTemplateProxy implements DistributedCache {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisDistributedProperties redisProperties;
    private final RedissonClient redissonClient;

    /**
     * 安全获取时的分布式锁 key 前缀
     */
    private static final String SAFE_GET_LOCK_PREFIX = "safe_get_distributed_lock:";


    @Override
    public <T> T get(String key, Class<T> clazz) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        // String 类型直接返回
        if (String.class.isAssignableFrom(clazz)) {
            return (T) value;
        }
        // 其他类型用 FastJson2 反序列化
        return JSON.parseObject(value, FastJson2Util.buildType(clazz));
    }

    @Override
    public void put(String key, Object value) {
        put(key, value, redisProperties.getValueTimeout());
    }



    @Override
    public Boolean delete(String key) {
        return stringRedisTemplate.delete(key);
    }

    @Override
    public Long delete(Collection<String> keys) {
        return stringRedisTemplate.delete(keys);
    }

    @Override
    public Boolean hasKey(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    @Override
    public Object getInstance() {
        return stringRedisTemplate;
    }



    @Override
    public <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader, long timeout) {
        return get(key, clazz, cacheLoader, timeout, redisProperties.getValueTimeUnit());
    }

    @Override
    public <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                     long timeout, TimeUnit timeUnit) {
        // 1. 先查缓存
        T result = get(key, clazz);
        if (!CacheUtil.isNullOrBlank(result)) {
            return result;
        }
        // 2. 缓存没命中，加载并写入（不加锁）
        return loadAndSet(key, cacheLoader, timeout, timeUnit, false, null);
    }



    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout) {
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getValueTimeUnit());
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit) {
        return safeGet(key, clazz, cacheLoader, timeout, timeUnit, null, null, null);
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter) {
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getValueTimeUnit(),
                bloomFilter, null, null);
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        return safeGet(key, clazz, cacheLoader, timeout, timeUnit, bloomFilter, null, null);
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheCheckFilter) {
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getValueTimeUnit(),
                bloomFilter, cacheCheckFilter, null);
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheCheckFilter) {
        return safeGet(key, clazz, cacheLoader, timeout, timeUnit,
                bloomFilter, cacheCheckFilter, null);
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheGetFilter,
                         CacheGetIfAbsent<String> cacheGetIfAbsent) {
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getValueTimeUnit(),
                bloomFilter, cacheGetFilter, cacheGetIfAbsent);
    }

    /**
     * 最终实现方法 — 所有 safeGet 重载最终都调用到这里
     * 防护能力：
     * 1. 布隆过滤器 → 防缓存穿透（bloomFilter 不为 null 时生效）
     * 2. CacheGetFilter → 解决布隆过滤器无法删除问题（cacheGetFilter 不为 null 时生效）
     * 3. 分布式锁 + 双重检查 → 防缓存击穿
     * 4. CacheGetIfAbsent → 缓存和 DB 都没数据时的后置处理
     */
    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit,
                         RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheGetFilter,
                         CacheGetIfAbsent<String> cacheGetIfAbsent) {
        // ===== 阶段1：查缓存 + 三重过滤 =====
        T result = get(key, clazz);

        // 条件A：缓存有值 → 直接返回
        // 条件B：cacheGetFilter 拦截 → 返回 null（解决布隆过滤器无法删除）
        // 条件C：布隆过滤器不包含此 key → 返回 null（防穿透）
        if (!CacheUtil.isNullOrBlank(result)
                || Optional.ofNullable(cacheGetFilter).map(each -> each.filter(key)).orElse(false)
                || Optional.ofNullable(bloomFilter).map(each -> !each.contains(key)).orElse(false)) {
            return result;
        }

        // ===== 阶段2：分布式锁 + 双重检查 + 加载 =====
        RLock lock = redissonClient.getLock(SAFE_GET_LOCK_PREFIX + key);
        lock.lock();
        try {
            // 双重检查：拿到锁后再查一次缓存（可能其他线程已加载完毕）
            if (CacheUtil.isNullOrBlank(result = get(key, clazz))) {
                // 缓存还是没有 → 调用 CacheLoader 从数据源加载
                if (CacheUtil.isNullOrBlank(result = loadAndSet(key, cacheLoader, timeout, timeUnit, true, bloomFilter))) {
                    // 数据源也没有 → 执行后置处理
                    Optional.ofNullable(cacheGetIfAbsent).ifPresent(each -> each.execute(key));
                }
            }
        } finally {
            lock.unlock();
        }
        return result;
    }



    @Override
    public void put(@NotBlank String key, Object value, long timeout) {
        put(key, value, timeout, redisProperties.getValueTimeUnit());
    }

    @Override
    public void put(@NotBlank String key, Object value, long timeout, TimeUnit timeUnit) {
        // String 直接存，对象用 FastJson2 序列化
        String actual = value instanceof String ? (String) value : JSON.toJSONString(value);
        stringRedisTemplate.opsForValue().set(key, actual, timeout, timeUnit);
    }



    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        RBloomFilter<String> bloomFilter) {
        safePut(key, value, timeout, redisProperties.getValueTimeUnit(), bloomFilter);
    }

    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        put(key, value, timeout, timeUnit);
        if (bloomFilter != null) {
            bloomFilter.add(key);
        }
    }



    @Override
    public Long countExistingKeys(@NotNull String... keys) {
        return stringRedisTemplate.countExistingKeys(Lists.newArrayList(keys));
    }

    /**
     * 从数据源加载数据并写入缓存
     *
     * @param safeFlag    是否安全模式（true → safePut 同时加入布隆过滤器，false → 普通 put）
     * @param bloomFilter 布隆过滤器（safeFlag=true 时使用）
     */
    private <T> T loadAndSet(String key, CacheLoader<T> cacheLoader, long timeout,
                             TimeUnit timeUnit, boolean safeFlag, RBloomFilter<String> bloomFilter) {
        T result = cacheLoader.load();
        if (CacheUtil.isNullOrBlank(result)) {
            return result;
        }
        if (safeFlag) {
            safePut(key, result, timeout, timeUnit, bloomFilter);
        } else {
            put(key, result, timeout, timeUnit);
        }
        return result;
    }
}