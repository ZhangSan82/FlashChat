package com.flashchat.cache;

import com.flashchat.cache.core.CacheGetFilter;
import com.flashchat.cache.core.CacheGetIfAbsent;
import com.flashchat.cache.core.CacheLoader;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.redisson.api.RBloomFilter;

import java.util.concurrent.TimeUnit;

/**
 * 分布式缓存
 */
public interface DistributedCache extends Cache{

    /**
     缓存没了自动加载，用默认时间单位
     */
    <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader, long timeout);

    /**
     缓存没了自动加载，指定时间单位
     */
    <T> T get(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader, long timeout, TimeUnit timeUnit);

    /**
     * 安全获取缓存 - 只防击穿
     * 通过分布式锁防止缓存击穿、缓存雪崩
     * 使用默认时间单位
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader, long timeout);

    /**
     * 安全获取缓存 - 只防击穿
     * 通过分布式锁防止缓存击穿、缓存雪崩
     * 自定义时间单位
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader, long timeout, TimeUnit timeUnit);


    /**
     * 安全获取缓存 - 防击穿 + 防穿透
     * 通过布隆过滤器防止缓存穿透
     * 通过分布式锁防止缓存击穿、缓存雪崩
     * 使用默认时间单位
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                  long timeout, RBloomFilter<String> bloomFilter);

    /**
     * 安全获取缓存 - 防击穿 + 防穿透
     * 自定义时间单位
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                  long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter);


    /**
     * 安全获取缓存 - 防击穿 + 防穿透 + 解决布隆过滤器无法删除
     * 使用默认时间单位
     *
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                  long timeout, RBloomFilter<String> bloomFilter,
                  CacheGetFilter<String> cacheCheckFilter);

    /**
     * 安全获取缓存 - 防击穿 + 防穿透 + 解决布隆过滤器无法删除
     * 自定义时间单位
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                  long timeout, TimeUnit timeUnit, RBloomFilter<String> bloomFilter,
                  CacheGetFilter<String> cacheCheckFilter);


    /**
     * 安全获取缓存 - 完整版
     * 使用默认时间单位
     *
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                  long timeout,
                  RBloomFilter<String> bloomFilter,
                  CacheGetFilter<String> cacheGetFilter,
                  CacheGetIfAbsent<String> cacheGetIfAbsent);

    /**
     * 安全获取缓存 - 完整版（最终方法，所有重载最终都调用到这里）
     * 自定义时间单位
     */
    <T> T safeGet(@NotBlank String key, Class<T> clazz, CacheLoader<T> cacheLoader,
                  long timeout, TimeUnit timeUnit,
                  RBloomFilter<String> bloomFilter,
                  CacheGetFilter<String> cacheGetFilter,
                  CacheGetIfAbsent<String> cacheGetIfAbsent);


    /**
     * 放入缓存，自定义超时时间，使用默认时间单位
     */
    void put(@NotBlank String key, Object value, long timeout);

    /**
     * 放入缓存，自定义超时时间和时间单位
     */
    void put(@NotBlank String key, Object value, long timeout, TimeUnit timeUnit);

    /**
     * 安全放入缓存（同时将key加入布隆过滤器）
     * 使用默认时间单位
     */
    void safePut(@NotBlank String key, Object value, long timeout,
                 RBloomFilter<String> bloomFilter);

    /**
     * 安全放入缓存（同时将key加入布隆过滤器）
     * 自定义时间单位
     */
    void safePut(@NotBlank String key, Object value, long timeout,
                 TimeUnit timeUnit, RBloomFilter<String> bloomFilter);


    /**
     * 统计指定 key 的存在数量
     * 例如：countExistingKeys("a", "b", "c")
     *       如果 a和b存在，c不存在 → 返回2
     */
    Long countExistingKeys(@NotNull String... keys);
}
