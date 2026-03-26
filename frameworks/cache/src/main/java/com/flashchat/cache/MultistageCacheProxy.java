package com.flashchat.cache;

import com.flashchat.cache.config.LocalCacheManager;
import com.flashchat.cache.core.CacheGetFilter;
import com.flashchat.cache.core.CacheGetIfAbsent;
import com.flashchat.cache.core.CacheLoader;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
/**
 * 多级缓存实现
 * <p>
 * 组合 StringRedisTemplateProxy（Redis）+ LocalCacheManager（Caffeine）
 * 读操作：先查本地 → miss 后委托 Redis 层 → 结果回填本地
 * 写操作：先写 Redis → 再写本地
 * 删除操作：先删 Redis → 再清本地
 */
@Slf4j
@RequiredArgsConstructor
public class MultistageCacheProxy implements MultistageCache {

    private final StringRedisTemplateProxy distributedCache;
    private final LocalCacheManager localCacheManager;

    /**
     * 本地未命中标记
     * 区分 localCacheManager.get() 返回 null（未命中）和 NULL_VALUE（命中空值标记）
     */
    private static final Object LOCAL_MISS = new Object();

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
    @SuppressWarnings("unchecked")
    public <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                     long timeout) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.get(key, clazz, cacheLoader, timeout);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                     long timeout, TimeUnit timeUnit) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.get(key, clazz, cacheLoader, timeout, timeUnit);
        fillLocal(key, result);
        return result;
    }

    // ==================== DistributedCache - safeGet（8 个重载） ====================

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout, bloomFilter);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit, bloomFilter);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheCheckFilter) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout,
                bloomFilter, cacheCheckFilter);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheCheckFilter) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit,
                bloomFilter, cacheCheckFilter);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheGetFilter,
                         CacheGetIfAbsent<String> cacheGetIfAbsent) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout,
                bloomFilter, cacheGetFilter, cacheGetIfAbsent);
        fillLocal(key, result);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                         long timeout, TimeUnit timeUnit,
                         RBloomFilter<String> bloomFilter,
                         CacheGetFilter<String> cacheGetFilter,
                         CacheGetIfAbsent<String> cacheGetIfAbsent) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }
        T result = distributedCache.safeGet(key, clazz, cacheLoader, timeout, timeUnit,
                bloomFilter, cacheGetFilter, cacheGetIfAbsent);
        fillLocal(key, result);
        return result;
    }

    // ==================== DistributedCache - put ====================

    @Override
    public void put(@NotBlank String key, Object value, long timeout) {
        distributedCache.put(key, value, timeout);
        localCacheManager.put(key, value);
    }

    @Override
    public void put(@NotBlank String key, Object value, long timeout, TimeUnit timeUnit) {
        distributedCache.put(key, value, timeout, timeUnit);
        localCacheManager.put(key, value);
    }

    // ==================== DistributedCache - safePut ====================

    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        RBloomFilter<String> bloomFilter) {
        distributedCache.safePut(key, value, timeout, bloomFilter);
        localCacheManager.put(key, value);
    }

    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        distributedCache.safePut(key, value, timeout, timeUnit, bloomFilter);
        localCacheManager.put(key, value);
    }

    // ==================== DistributedCache - countExistingKeys ====================

    @Override
    public Long countExistingKeys(@NotNull String... keys) {
        return distributedCache.countExistingKeys(keys);
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
}
