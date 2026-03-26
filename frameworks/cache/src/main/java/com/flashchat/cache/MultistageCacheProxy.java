package com.flashchat.cache;

import com.flashchat.cache.config.LocalCacheManager;
import com.flashchat.cache.core.CacheGetFilter;
import com.flashchat.cache.core.CacheGetIfAbsent;
import com.flashchat.cache.core.CacheLoader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 多级缓存实现
 * <p>
 * 组合 StringRedisTemplateProxy（Redis）+ LocalCacheManager（Caffeine）
 * 读操作：先查本地 → miss 后委托 Redis 层 → 结果回填本地
 * 写操作：先写 Redis → 再写本地
 * 删除操作：先删 Redis → 再清本地
 */
@Slf4j
public class MultistageCacheProxy implements MultistageCache {

    private final StringRedisTemplateProxy distributedCache;
    private final LocalCacheManager localCacheManager;


    /**
     * 本地未命中标记
     * 区分 localCacheManager.get() 返回 null（未命中）和 NULL_VALUE（命中空值标记）
     */
    private static final Object LOCAL_MISS = new Object();

    /**
     * Redis 降级计数器（按操作类型预创建）
     * <p>
     * 在构造函数中一次性创建所有 Counter 对象，避免高频降级时
     * 每次调用 meterRegistry.counter(name, tags) 的 ConcurrentHashMap 查找 + Tag 字符串拼接开销。
     * <p>
     * key = 操作名称（get/safeGet/put/safePut/delete/hasKey/countExistingKeys）
     * value = 预创建的 Micrometer Counter
     */
    private final Map<String, Counter> degradationCounters;

    /**
     * @param meterRegistry 监控注册器，为 null 时不记录指标
     */
    public MultistageCacheProxy(StringRedisTemplateProxy distributedCache,
                                LocalCacheManager localCacheManager,
                                @Nullable MeterRegistry meterRegistry) {
        this.distributedCache = distributedCache;
        this.localCacheManager = localCacheManager;

        // 预创建所有降级计数器
        if (meterRegistry != null) {
            this.degradationCounters = Map.of(
                    "get", meterRegistry.counter("cache.redis.degradation", "operation", "get"),
                    "safeGet", meterRegistry.counter("cache.redis.degradation", "operation", "safeGet"),
                    "put", meterRegistry.counter("cache.redis.degradation", "operation", "put"),
                    "safePut", meterRegistry.counter("cache.redis.degradation", "operation", "safePut"),
                    "delete", meterRegistry.counter("cache.redis.degradation", "operation", "delete"),
                    "hasKey", meterRegistry.counter("cache.redis.degradation", "operation", "hasKey"),
                    "countExistingKeys", meterRegistry.counter("cache.redis.degradation", "operation", "countExistingKeys")
            );
        } else {
            this.degradationCounters = null;
        }
    }

    // ==================== 本地缓存公共逻辑 ====================

    /**
     * 查询本地缓存
     *
     * @return LOCAL_MISS 表示未命中，NULL_VALUE 表示命中空值标记，其他表示命中有效值
     */
    private Object getFromLocal(String key) {
        Object value = localCacheManager.get(key);
        return value != null ? value : LOCAL_MISS;
    }

    /**
     * 解析本地缓存命中值
     * 将 NULL_VALUE 标记转换为 null，有效值强转为目标类型
     */
    @SuppressWarnings("unchecked")
    private <T> T resolveLocal(Object local) {
        return LocalCacheManager.isNullValue(local) ? null : (T) local;
    }

    /**
     * 回填本地缓存
     * 有效值写入正常缓存，null 值写入空值标记
     */
    private <T> void fillLocal(String key, T result) {
        if (result != null) {
            localCacheManager.put(key, result);
        } else {
            localCacheManager.putNullValue(key);
        }
    }

    // ==================== Cache 基础方法 ====================

    /**
     * 简单读取 — 查本地但不回填
     * 无 CacheLoader，无法确认 key 是否存在，不做回填以免缓存错误的 null
     */
    @Override
    public <T> T get(@NotBlank String key, Class<T> clazz) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        return distributedCache.get(key, clazz);
    }

    @Override
    public void put(@NotBlank String key, Object value) {
        distributedCache.put(key, value);
        localCacheManager.put(key, value);
    }

    @Override
    public Boolean delete(@NotBlank String key) {
        Boolean result = distributedCache.delete(key);
        localCacheManager.invalidate(key);
        return result;
    }

    @Override
    public Long delete(@NotNull Collection<String> keys) {
        Long result = distributedCache.delete(keys);
        if (keys != null) {
            keys.forEach(localCacheManager::invalidate);
        }
        return result;
    }

    @Override
    public Boolean hasKey(@NotBlank String key) {
        return distributedCache.hasKey(key);
    }

    @Override
    public Object getInstance() {
        return distributedCache.getInstance();
    }

    // ==================== DistributedCache - get with CacheLoader ====================

    @Override
    public <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                     long timeout) {
        return doGetWithDegradation("get", key, cacheLoader,
                () -> distributedCache.get(key, clazz, cacheLoader, timeout));
    }

    @Override
    public <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                     long timeout, TimeUnit timeUnit) {
        return doGetWithDegradation("get", key, cacheLoader,
                () -> distributedCache.get(key, clazz, cacheLoader, timeout, timeUnit));
    }

    // ==================== DistributedCache - safeGet（8 个重载） ====================

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout));
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit));
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout, bloomFilter));
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit,
                        bloomFilter));
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheCheckFilter) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout,
                        bloomFilter, cacheCheckFilter));
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheCheckFilter) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit,
                        bloomFilter, cacheCheckFilter));
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheGetFilter,
                         CacheGetIfAbsent<String> cacheGetIfAbsent) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout,
                        bloomFilter, cacheGetFilter, cacheGetIfAbsent));
    }

    @Override
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit,
                         RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheGetFilter,
                         CacheGetIfAbsent<String> cacheGetIfAbsent) {
        return doGetWithDegradation("safeGet", key, cacheLoader,
                () -> distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit,
                        bloomFilter, cacheGetFilter, cacheGetIfAbsent));
    }

    // ==================== DistributedCache - put ====================

    @Override
    public void put(@NotBlank String key, Object value, long timeout) {
        safeDistributedWrite("put", key,
                () -> distributedCache.put(key, value, timeout));
        localCacheManager.put(key, value);
    }

    @Override
    public void put(@NotBlank String key, Object value, long timeout, TimeUnit timeUnit) {
        safeDistributedWrite("put", key,
                () -> distributedCache.put(key, value, timeout, timeUnit));
        localCacheManager.put(key, value);
    }

    // ==================== DistributedCache - safePut ====================

    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        RBloomFilter<String> bloomFilter) {
        safeDistributedWrite("safePut", key,
                () -> distributedCache.safePut(key, value, timeout, bloomFilter));
        localCacheManager.put(key, value);
    }

    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        safeDistributedWrite("safePut", key,
                () -> distributedCache.safePut(key, value, timeout, timeUnit, bloomFilter));
        localCacheManager.put(key, value);
    }

    // ==================== DistributedCache - countExistingKeys ====================

    @Override
    public Long countExistingKeys(@NotNull String... keys) {
        try {
            return distributedCache.countExistingKeys(keys);
        } catch (Exception e) {
            log.error("[Cache] Redis countExistingKeys 异常", e);
            recordDegradation("countExistingKeys");
            return 0L;
        }
    }

    // ==================== MultistageCache 新增方法 ====================

    @Override
    public void invalidateLocal(String key) {
        localCacheManager.invalidate(key);
    }

    @Override
    public boolean isLocalCacheEnabled() {
        return localCacheManager.isAvailable();
    }



    /**
     * 带降级保护的分布式缓存读取（改进3 核心方法）
     * <p>
     * 统一处理：本地缓存检查 → 分布式缓存调用 → 异常降级 → 回填本地缓存
     * <p>
     * get / safeGet 的 10 个重载全部委托此方法，消除重复的 try-catch 代码。
     * 降级时绕过 Redis 直接查数据源，结果仅回填本地缓存（Redis 可能仍不可用）。
     *
     * @param operation         操作名称，用于日志和监控指标
     * @param key               缓存 key
     * @param cacheLoader       数据源加载器，降级时直接调用
     * @param distributedAction 分布式缓存操作（正常路径）
     */
    private <T> T doGetWithDegradation(String operation, String key,
                                       CacheLoader<T> cacheLoader,
                                       Supplier<T> distributedAction) {
        // 1. 查本地缓存
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }

        // 2. 查分布式缓存（带降级保护）
        try {
            T result = distributedAction.get();
            fillLocal(key, result);
            return result;
        } catch (Exception e) {
            log.error("[Cache] Redis {} 异常, key={}, 降级查数据源", operation, key, e);
            recordDegradation(operation);
            return loadDirectAndFillLocal(key, cacheLoader);
        }
    }

    /**
     * 带降级保护的分布式缓存写入
     * <p>
     * Redis 写入失败时仅记录日志和指标，不影响后续的本地缓存写入。
     * 调用方在此方法之后仍会执行 localCacheManager.put()。
     *
     * @param operation 操作名称
     * @param key       缓存 key
     * @param action    分布式缓存写操作
     */
    private void safeDistributedWrite(String operation, String key, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[Cache] Redis {} 异常, key={}, 仅写本地", operation, key, e);
            recordDegradation(operation);
        }
    }

    /**
     * Redis 不可用时的降级加载
     * <p>
     * 直接走数据源加载 + 回填本地缓存。
     * 不写 Redis（因为 Redis 可能仍不可用），数据仅存活在本地缓存中，
     * 本地缓存过期后会再次尝试 Redis，届时 Redis 可能已恢复。
     */
    private <T> T loadDirectAndFillLocal(String key, CacheLoader<T> cacheLoader) {
        try {
            T result = cacheLoader.load();
            fillLocal(key, result);
            return result;
        } catch (Exception e) {
            log.error("[Cache] 降级加载数据源也失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 记录 Redis 降级事件
     * <p>
     * 使用构造函数中预创建的 Counter，避免高频降级时的 HashMap 查找和 Tag 拼接开销。
     * 正常情况下 cache.redis.degradation 计数应为 0，
     * 非 0 表示 Redis 异常，需要关注。
     * <p>
     * Prometheus 查询示例：rate(cache_redis_degradation_total[5m]) > 0
     */
    private void recordDegradation(String operation) {
        if (degradationCounters != null) {
            Counter counter = degradationCounters.get(operation);
            if (counter != null) {
                counter.increment();
            }
        }
    }
}
