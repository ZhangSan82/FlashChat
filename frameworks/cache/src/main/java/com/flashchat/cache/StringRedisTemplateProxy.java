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
import java.util.concurrent.ThreadLocalRandom;
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

    /**
     * 降级重试次数
     */
    private static final int DEGRADATION_RETRY_TIMES = 2;

    /**
     * 降级重试间隔（ms）
     */
    private static final long DEGRADATION_RETRY_INTERVAL_MS = 100L;


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
        put(key, value, redisProperties.getRedis().getValueTimeout());
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
        return get(key, clazz, cacheLoader, timeout, redisProperties.getRedis().getValueTimeUnit());
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
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getRedis().getValueTimeUnit());
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit) {
        return safeGet(key, clazz, cacheLoader, timeout, timeUnit, null, null, null);
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter) {
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getRedis().getValueTimeUnit(),
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
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getRedis().getValueTimeUnit(),
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
        return safeGet(key, clazz, cacheLoader, timeout, redisProperties.getRedis().getValueTimeUnit(),
                bloomFilter, cacheGetFilter, cacheGetIfAbsent);
    }

    /**
     * 最终实现方法 — 所有 safeGet 重载最终都调用到这里
     * <p>
     * 防护能力：
     * 1. 布隆过滤器 → 防缓存穿透（bloomFilter 不为 null 时生效）
     * 2. CacheGetFilter → 解决布隆过滤器无法删除问题（cacheGetFilter 不为 null 时生效）
     * 3. 分布式锁(tryLock + 超时) + 双重检查 → 防缓存击穿
     * 4. CacheGetIfAbsent → 缓存和 DB 都没数据时的后置处理
     * 5. 锁获取超时时的降级策略 → 重试查缓存 + 降级查数据源
     */
    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit,
                         RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheGetFilter,
                         CacheGetIfAbsent<String> cacheGetIfAbsent) {
        // ===== 阶段1：查缓存 + 三重过滤 =====
        T result = get(key, clazz);

        // 条件A：缓存命中 → 直接返回
        if (!CacheUtil.isNullOrBlank(result)) {
            return result;
        }

        // 条件B：自定义过滤器拦截 → 返回 null（弥补布隆过滤器无法删除的问题）
        if (cacheGetFilter != null && cacheGetFilter.filter(key)) {
            log.debug("[Cache] CacheGetFilter 拦截, key={}", key);
            return null;
        }

        // 条件C：布隆过滤器判定 key 不存在 → 返回 null（防穿透）
        if (bloomFilter != null && !bloomFilter.contains(key)) {
            log.debug("[Cache] BloomFilter 拦截, key={}", key);
            return null;
        }

        // ===== 阶段2：分布式锁 + 双重检查 + 加载（tryLock超时） =====
        RLock lock = redissonClient.getLock(SAFE_GET_LOCK_PREFIX + key);
        boolean acquired;
        try {
            acquired = lock.tryLock(
                    redisProperties.getLockWaitTime(),
                    redisProperties.getLockLeaseTime(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Cache] 获取分布式锁被中断, key={}", key);
            return null;
        }

        // ===== 获取锁超时 → 降级策略 =====
        if (!acquired) {
            return onLockTimeout(key, clazz, cacheLoader);
        }

        // ===== 获取锁成功 → 双重检查 + 数据加载 =====
        try {
            // 双重检查：拿到锁后再查一次缓存（可能其他线程已加载完毕）
            if (CacheUtil.isNullOrBlank(result = get(key, clazz))) {
                // 缓存还是没有 → 调用 CacheLoader 从数据源加载
                if (CacheUtil.isNullOrBlank(result = loadAndSet(key, cacheLoader, timeout,
                        timeUnit, true, bloomFilter))) {
                    // 数据源也没有 → 执行后置处理
                    Optional.ofNullable(cacheGetIfAbsent).ifPresent(each -> each.execute(key));
                }
            }
        } finally {
            // ===== 安全释放锁 =====
            safeUnlock(lock, key);
        }

        return result;
    }


    @Override
    public void put(@NotBlank String key, Object value, long timeout) {
        put(key, value, timeout, redisProperties.getRedis().getValueTimeUnit());
    }

    @Override
    public void put(@NotBlank String key, Object value, long timeout, TimeUnit timeUnit) {
        // String 直接存，对象用 FastJson2 序列化
        String actual = value instanceof String ? (String) value : JSON.toJSONString(value);
        // 过期时间随机化，防止大量 key 同时过期（缓存雪崩）
        long actualTimeout = redisProperties.isTimeoutRandomEnabled()
                ? randomizeTimeout(timeout)
                : timeout;
        stringRedisTemplate.opsForValue().set(key, actual, actualTimeout, timeUnit);
    }


    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        RBloomFilter<String> bloomFilter) {
        safePut(key, value, timeout, redisProperties.getRedis().getValueTimeUnit(), bloomFilter);
    }

    /**
     * 先加布隆过滤器，再写 Redis
     * <p>
     * 原因：如果先写 Redis 后加布隆，中间失败会导致 safeGet 的布隆判断"不存在"而读不到数据。
     * 先加布隆后写 Redis，最坏情况是布隆说"存在"但 Redis 没有，safeGet 会走正常的回源逻辑，不会丢数据。
     * <p>
     * 核心原则：布隆过滤器的"判断不存在则一定不存在"特性，决定了宁可多加（误判存在），不能漏加（拦截真实数据）。
     */
    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        if (bloomFilter != null) {
            bloomFilter.add(key);
        }
        put(key, value, timeout, timeUnit);
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


    /**
     * ：获取锁超时时的降级策略
     * <p>
     * 策略：先短暂等待 + 重试查缓存（大概率持锁线程已完成回填），仍未命中再降级查数据源。
     * 降级查数据源时不写缓存，避免与持锁线程的写操作产生并发冲突。
     */
    private <T> T onLockTimeout(String key, Class<T> clazz, CacheLoader<T> cacheLoader) {
        log.warn("[Cache] 获取分布式锁超时, key={}, 进入降级策略", key);

        // 重试：短暂等待后查缓存，给持锁线程完成回填的机会
        for (int i = 0; i < DEGRADATION_RETRY_TIMES; i++) {
            try {
                Thread.sleep(DEGRADATION_RETRY_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            T result = get(key, clazz);
            if (!CacheUtil.isNullOrBlank(result)) {
                log.info("[Cache] 降级重试第{}次命中缓存, key={}", i + 1, key);
                return result;
            }
        }

        // 重试后仍未命中 → 降级直接查数据源（不写缓存）
        log.warn("[Cache] 降级重试未命中, 直接查数据源, key={}", key);
        try {
            return cacheLoader.load();
        } catch (Exception e) {
            log.error("[Cache] 降级加载数据源失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 安全释放分布式锁
     * <p>
     * 使用 tryLock + leaseTime 后，锁可能在业务执行期间自动过期释放。
     * 此时直接 unlock() 会抛 IllegalMonitorStateException（锁已不属于当前线程）。
     * 必须先检查 isHeldByCurrentThread()，并 catch 所有异常防止影响业务返回值。
     */
    private void safeUnlock(RLock lock, String key) {
        if (lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.warn("[Cache] 释放分布式锁异常, key={}", key, e);
            }
        } else {
            // 锁已不属于当前线程 → leaseTime 过期被自动释放
            // 如果此日志频繁出现，说明 leaseTime 配置过短，需要调大
            log.warn("[Cache] 锁已自动释放(业务执行超过leaseTime), key={}", key);
        }
    }

    /**
     * 生成随机化过期时间，防止缓存雪崩
     * <p>
     * 在原始 timeout 基础上增加 ±ratio 的随机浮动。
     * 例如 timeout=60000, ratio=0.1 → 实际 TTL 范围 [54000, 66000]。
     * <p>
     * 边界处理：
     * - timeout <= 0：不随机化（可能是特殊语义）
     * - fluctuation == 0（timeout 极小如 1-9）：不随机化（短 TTL 通常是精确控制的）
     */
    private long randomizeTimeout(long timeout) {
        if (timeout <= 0) {
            return timeout;
        }
        long fluctuation = (long) (timeout * redisProperties.getTimeoutRandomRatio());
        if (fluctuation == 0) {
            return timeout;
        }
        return timeout + ThreadLocalRandom.current().nextLong(-fluctuation, fluctuation + 1);
    }
}