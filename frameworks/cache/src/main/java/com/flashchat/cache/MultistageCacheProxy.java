package com.flashchat.cache;

import com.flashchat.cache.config.LocalCacheManager;
import com.flashchat.cache.core.CacheGetFilter;
import com.flashchat.cache.core.CacheGetIfAbsent;
import com.flashchat.cache.core.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 多级缓存实现。
 * <p>
 * 组合 StringRedisTemplateProxy（Redis）+ LocalCacheManager（Caffeine）+ RedisCircuitBreaker（熔断）
 * <p>
 * 这层代理要同时解决四件事：
 * 1. 读路径优先命中本地缓存，减少 Redis RTT
 * 2. Redis 半故障时快速熔断，避免每次都等超时
 * 3. Redis 写失败 / 删失败后，当前节点仍然保持正确语义
 * 4. Redis 恢复后，能够把故障期遗留的「待修复操作」补回去
 * <p>
 * 核心链路：
 * 读：本地缓存 -> 待修复标记 -> 熔断器 -> Redis -> 数据源
 * 写：Redis（可能熔断/失败）-> 记录待修复操作 -> 本地缓存
 * 删：Redis（可能熔断/失败）-> 记录待修复操作 -> finally 清本地
 */
@Slf4j
public class MultistageCacheProxy implements MultistageCache {

    /**
     * 本地未命中标记。
     * 区分：
     * 1. localCacheManager.get() 返回 null：真正未命中
     * 2. localCacheManager.get() 返回 NULL_VALUE：命中了「空值标记」
     */
    private static final Object LOCAL_MISS = new Object();

    /**
     * 待修复操作在本地保留的时间（分钟）。
     * 目标不是永久可靠补偿，而是在单机故障恢复窗口内兜住最危险的一段时间。
     */
    private static final long PENDING_REPAIR_TTL_MINUTES = 5L;

    /**
     * 待修复操作的最大缓存条目数。
     */
    private static final long PENDING_REPAIR_MAX_SIZE = 50_000L;

    /**
     * Redis 访问代理。
     */
    private final StringRedisTemplateProxy distributedCache;

    /**
     * 本地缓存管理器。
     */
    private final LocalCacheManager localCacheManager;

    /**
     * Redis 熔断器。
     */
    private final RedisCircuitBreaker circuitBreaker;

    /**
     * 待修复操作表。
     * <p>
     * 记录 Redis 故障期未成功落盘的动作：
     * 1. UPSERT     写入失败，本地已经有新值，但 Redis 可能还是旧值
     * 2. INVALIDATE 删除失败，Redis 可能残留旧值，后续读不能直接再信 Redis
     * <p>
     * 这是本次实现里最关键的正确性保护，不然 Redis 恢复后旧值会重新污染本地缓存。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, PendingRedisOperation>
            pendingRedisRepairs = Caffeine.newBuilder()
            .maximumSize(PENDING_REPAIR_MAX_SIZE)
            .expireAfterWrite(PENDING_REPAIR_TTL_MINUTES, TimeUnit.MINUTES)
            .build();

    /**
     * Redis 降级计数器，按操作名称预创建，避免高频降级时重复做 meterRegistry.counter(...) 查找。
     */
    private final Map<String, Counter> degradationCounters;

    /**
     * @param distributedCache Redis 访问代理
     * @param localCacheManager 本地缓存管理器
     * @param circuitBreaker Redis 熔断器
     * @param meterRegistry 监控注册器，为 null 时不记录降级指标
     */
    public MultistageCacheProxy(StringRedisTemplateProxy distributedCache,
                                LocalCacheManager localCacheManager,
                                RedisCircuitBreaker circuitBreaker,
                                @Nullable MeterRegistry meterRegistry) {
        this.distributedCache = distributedCache;
        this.localCacheManager = localCacheManager;
        this.circuitBreaker = circuitBreaker;

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

    /**
     * 查询本地缓存。
     *
     * @return LOCAL_MISS 代表未命中；其他值代表命中（包含 NULL_VALUE 标记）
     */
    private Object getFromLocal(String key) {
        Object value = localCacheManager.get(key);
        return value != null ? value : LOCAL_MISS;
    }

    /**
     * 解析本地缓存命中的值。
     * NULL_VALUE 要转换成业务语义上的 null。
     */
    @SuppressWarnings("unchecked")
    private <T> T resolveLocal(Object local) {
        return LocalCacheManager.isNullValue(local) ? null : (T) local;
    }

    /**
     * 回填本地缓存。
     * 有效值正常写入；null 使用独立的空值标记，便于用更短 TTL 防穿透。
     */
    private <T> void fillLocal(String key, T result) {
        if (result != null) {
            localCacheManager.put(key, result);
        } else {
            localCacheManager.putNullValue(key);
        }
    }

    /**
     * 简单读取。
     * <p>
     * 和带 CacheLoader 的 get/safeGet 不同，这里没有数据源回源能力，因此策略更保守：
     * 1. 先查本地
     * 2. 命中待修复 UPSERT 时，直接返回待修复的新值，禁止回 Redis 读旧值
     * 3. 命中待修复 INVALIDATE 时，返回 null，避免旧值回流
     * 4. 熔断中直接返回 null，不去碰 Redis
     * 5. Redis 正常时才真正访问 Redis
     */
    @Override
    public <T> T get(@NotBlank String key, Class<T> clazz) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }

        PendingRedisOperation pendingOperation = pendingRedisRepairs.getIfPresent(key);
        if (pendingOperation != null) {
            recordDegradation("get");
            if (pendingOperation.type == PendingOperationType.UPSERT) {
                T value = resolvePendingValue(pendingOperation);
                fillLocal(key, value);
                return value;
            }
            return null;
        }

        if (!circuitBreaker.allowRequest()) {
            recordDegradation("get");
            return null;
        }

        try {
            T result = distributedCache.get(key, clazz);
            circuitBreaker.recordSuccess();
            if (result != null) {
                localCacheManager.put(key, result);
            }
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[Cache] 简单 get 异常, key={}, 返回 null", key, e);
            recordDegradation("get");
            return null;
        }
    }

    /**
     * 无 TTL 的 put。
     * Redis 写失败时不会抛异常给业务，而是：
     * 1. 记录待修复 UPSERT
     * 2. 仍然先把本地缓存更新成功
     */
    @Override
    public void put(@NotBlank String key, Object value) {
        safeDistributedWrite(
                "put",
                key,
                () -> distributedCache.put(key, value),
                PendingRedisOperation.upsert(value, () -> distributedCache.put(key, value))
        );
        localCacheManager.put(key, value);
    }

    /**
     * 单 key 删除。
     * <p>
     * finally 一定清本地，这是删除语义里最重要的一点：
     * 即使 Redis 删除失败，当前节点也不能继续提供旧值。
     * <p>
     * 如果 Redis 删除失败，则记录待修复 INVALIDATE，后续读会绕开 Redis 的旧值。
     */
    @Override
    public Boolean delete(@NotBlank String key) {
        PendingRedisOperation pendingOperation =
                PendingRedisOperation.invalidate(() -> distributedCache.delete(key));

        try {
            if (!circuitBreaker.allowRequest()) {
                rememberPendingRedisRepair(key, pendingOperation);
                recordDegradation("delete");
                return false;
            }
            Boolean result = distributedCache.delete(key);
            circuitBreaker.recordSuccess();
            clearPendingRedisRepair(key);
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            rememberPendingRedisRepair(key, pendingOperation);
            log.error("[Cache] Redis delete 异常, key={}", key, e);
            recordDegradation("delete");
            return false;
        } finally {
            localCacheManager.invalidate(key);
        }
    }

    /**
     * 批量删除。
     * 语义与单 key 删除一致：Redis 成功最好；失败也必须清本地，并留下待修复 INVALIDATE。
     */
    @Override
    public Long delete(@NotNull Collection<String> keys) {
        try {
            if (!circuitBreaker.allowRequest()) {
                rememberPendingInvalidate(keys);
                recordDegradation("delete");
                return 0L;
            }

            Long result = distributedCache.delete(keys);
            circuitBreaker.recordSuccess();
            if (keys != null) {
                keys.forEach(this::clearPendingRedisRepair);
            }
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            rememberPendingInvalidate(keys);
            log.error("[Cache] Redis batch delete 异常, keys.size={}", keys != null ? keys.size() : 0, e);
            recordDegradation("delete");
            return 0L;
        } finally {
            if (keys != null) {
                keys.forEach(localCacheManager::invalidate);
            }
        }
    }

    /**
     * hasKey 的降级策略更保守。
     * <p>
     * 1. 如果本地有待修复 UPSERT，说明当前节点知道这个 key 应该存在，直接返回 true
     * 2. 如果本地有待修复 INVALIDATE，说明 Redis 可能还有残留旧值，直接返回 false
     * 3. 熔断中也返回 false，避免为一个辅助判断把请求卡死在 Redis 上
     */
    @Override
    public Boolean hasKey(@NotBlank String key) {
        PendingRedisOperation pendingOperation = pendingRedisRepairs.getIfPresent(key);
        if (pendingOperation != null) {
            return pendingOperation.type == PendingOperationType.UPSERT;
        }

        if (!circuitBreaker.allowRequest()) {
            recordDegradation("hasKey");
            return false;
        }

        try {
            Boolean result = distributedCache.hasKey(key);
            circuitBreaker.recordSuccess();
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("[Cache] Redis hasKey 异常, key={}", key, e);
            recordDegradation("hasKey");
            return false;
        }
    }

    @Override
    public Object getInstance() {
        return distributedCache.getInstance();
    }

    /**
     * 普通 get + CacheLoader。
     * 统一走 doGetWithDegradation，避免每个重载都复制一套熔断与补偿逻辑。
     */
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

    /**
     * 以下 safeGet 的多个重载最终都委托到同一套带熔断 / 待修复 / 回源逻辑里。
     * 这样可以保证 BloomFilter、CacheGetFilter 等高阶能力与熔断机制的一致性。
     */
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

    /**
     * 带 TTL 的 put。
     * 失败时记录待修复 UPSERT，恢复后自动尝试回放。
     */
    @Override
    public void put(@NotBlank String key, Object value, long timeout) {
        safeDistributedWrite(
                "put",
                key,
                () -> distributedCache.put(key, value, timeout),
                PendingRedisOperation.upsert(value, () -> distributedCache.put(key, value, timeout))
        );
        localCacheManager.put(key, value);
    }

    /**
     * 带 TimeUnit 的 put。
     */
    @Override
    public void put(@NotBlank String key, Object value, long timeout, TimeUnit timeUnit) {
        safeDistributedWrite(
                "put",
                key,
                () -> distributedCache.put(key, value, timeout, timeUnit),
                PendingRedisOperation.upsert(
                        value,
                        () -> distributedCache.put(key, value, timeout, timeUnit)
                )
        );
        localCacheManager.put(key, value);
    }

    /**
     * safePut 会同时维护 BloomFilter。
     * Redis 层失败时同样记录待修复 UPSERT，避免后续又把旧值读回来。
     */
    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        RBloomFilter<String> bloomFilter) {
        safeDistributedWrite(
                "safePut",
                key,
                () -> distributedCache.safePut(key, value, timeout, bloomFilter),
                PendingRedisOperation.upsert(
                        value,
                        () -> distributedCache.safePut(key, value, timeout, bloomFilter)
                )
        );
        localCacheManager.put(key, value);
    }

    /**
     * 带 TimeUnit 的 safePut。
     */
    @Override
    public void safePut(@NotBlank String key, Object value, long timeout,
                        TimeUnit timeUnit, RBloomFilter<String> bloomFilter) {
        safeDistributedWrite(
                "safePut",
                key,
                () -> distributedCache.safePut(key, value, timeout, timeUnit, bloomFilter),
                PendingRedisOperation.upsert(
                        value,
                        () -> distributedCache.safePut(key, value, timeout, timeUnit, bloomFilter)
                )
        );
        localCacheManager.put(key, value);
    }

    /**
     * countExistingKeys 没有合适的数据源回退路径，因此熔断时直接返回 0。
     * 这是一个偏保守的降级策略：宁可误判，也不把线程耗在 Redis 超时上。
     */
    @Override
    public Long countExistingKeys(@NotNull String... keys) {
        if (!circuitBreaker.allowRequest()) {
            recordDegradation("countExistingKeys");
            return 0L;
        }

        try {
            Long result = distributedCache.countExistingKeys(keys);
            circuitBreaker.recordSuccess();
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("[Cache] Redis countExistingKeys 异常", e);
            recordDegradation("countExistingKeys");
            return 0L;
        }
    }

    @Override
    public void invalidateLocal(String key) {
        localCacheManager.invalidate(key);
    }

    @Override
    public boolean isLocalCacheEnabled() {
        return localCacheManager.isAvailable();
    }

    @Override
    public String getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    /**
     * 带熔断保护的统一读入口。
     * <p>
     * 完整链路：
     * 1. 查本地缓存
     * 2. 查待修复表
     * 3. 检查熔断器
     * 4. 访问 Redis
     * 5. Redis 失败则回源 DB
     * 6. 结果回填本地缓存
     * <p>
     * 设计重点：
     * 待修复表优先级高于 Redis，可用性永远不能覆盖正确性语义。
     */
    private <T> T doGetWithDegradation(String operation, String key,
                                       CacheLoader<T> cacheLoader,
                                       Supplier<T> distributedAction) {
        Object local = getFromLocal(key);
        if (local != LOCAL_MISS) {
            return resolveLocal(local);
        }

        PendingRedisOperation pendingOperation = pendingRedisRepairs.getIfPresent(key);
        if (pendingOperation != null) {
            return resolvePendingOperation(operation, key, cacheLoader, pendingOperation);
        }

        if (!circuitBreaker.allowRequest()) {
            log.debug("[Cache] 熔断中，跳过 Redis, key={}", key);
            recordDegradation(operation);
            return loadDirectAndFillLocal(key, cacheLoader);
        }

        try {
            T result = distributedAction.get();
            circuitBreaker.recordSuccess();
            fillLocal(key, result);
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("[Cache] Redis {} 异常, key={}, 降级查数据源", operation, key, e);
            recordDegradation(operation);
            return loadDirectAndFillLocal(key, cacheLoader);
        }
    }

    /**
     * 带熔断保护的统一写入口。
     * <p>
     * 熔断中：直接跳过 Redis，记录待修复操作
     * Redis 异常：记录待修复操作
     * Redis 成功：清理待修复操作
     * <p>
     * 调用方在该方法之后仍会把本地缓存写成功，保证当前节点语义优先。
     */
    private void safeDistributedWrite(String operation,
                                      String key,
                                      Runnable action,
                                      PendingRedisOperation pendingOperation) {
        if (!circuitBreaker.allowRequest()) {
            log.debug("[Cache] 熔断中，跳过 Redis {}，key={}", operation, key);
            recordDegradation(operation);
            rememberPendingRedisRepair(key, pendingOperation);
            return;
        }

        try {
            action.run();
            circuitBreaker.recordSuccess();
            clearPendingRedisRepair(key);
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("[Cache] Redis {} 异常, key={}, 仅写本地", operation, key, e);
            recordDegradation(operation);
            rememberPendingRedisRepair(key, pendingOperation);
        }
    }

    /**
     * Redis 不可用时的直接回源逻辑。
     * 只回填本地缓存，不立刻写 Redis，因为此时 Redis 很可能仍不可用。
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
     * 处理待修复操作。
     * <p>
     * UPSERT：
     * - 当前节点已经知道新值
     * - 先尝试回放 Redis 修复
     * - 无论回放是否成功，本次都先返回新值，禁止去 Redis 读旧值
     * <p>
     * INVALIDATE：
     * - Redis 删除失败，Redis 可能残留旧值
     * - 先回源数据源，拿到最新值后再尝试重建 Redis
     * - 如果数据源也为空，则继续尝试回放删除动作
     */
    private <T> T resolvePendingOperation(String operation,
                                          String key,
                                          CacheLoader<T> cacheLoader,
                                          PendingRedisOperation pendingOperation) {
        recordDegradation(operation);

        if (pendingOperation.type == PendingOperationType.UPSERT) {
            tryReplayPendingOperation(key, pendingOperation);
            T value = resolvePendingValue(pendingOperation);
            fillLocal(key, value);
            return value;
        }

        T result = loadDirectAndFillLocal(key, cacheLoader);
        if (result != null) {
            PendingRedisOperation freshValueRepair = PendingRedisOperation.upsert(
                    result,
                    () -> distributedCache.put(key, result)
            );
            if (!tryReplayPendingOperation(key, freshValueRepair)) {
                rememberPendingRedisRepair(key, freshValueRepair);
            }
            return result;
        }

        if (!tryReplayPendingOperation(key, pendingOperation)) {
            rememberPendingRedisRepair(key, pendingOperation);
        }
        return null;
    }

    /**
     * 待修复 UPSERT 中存放的是业务值，读取时做一次类型转换。
     */
    @SuppressWarnings("unchecked")
    private <T> T resolvePendingValue(PendingRedisOperation pendingOperation) {
        return (T) pendingOperation.value;
    }

    /**
     * 尝试回放待修复操作。
     * <p>
     * 只有熔断器允许访问 Redis 时才真正执行。
     * 成功则清理待修复标记；失败则继续保留，等待下一次机会。
     */
    private boolean tryReplayPendingOperation(String key, PendingRedisOperation pendingOperation) {
        if (!circuitBreaker.allowRequest()) {
            return false;
        }

        try {
            pendingOperation.replayAction.run();
            circuitBreaker.recordSuccess();
            clearPendingRedisRepair(key);
            return true;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("[Cache] Redis 修复回放失败, key={}, type={}",
                    key, pendingOperation.type, e);
            return false;
        }
    }

    /**
     * 记录单个 key 的待修复操作。
     */
    private void rememberPendingRedisRepair(String key, PendingRedisOperation pendingOperation) {
        pendingRedisRepairs.put(key, pendingOperation);
    }

    /**
     * 修复成功后移除待修复标记。
     */
    private void clearPendingRedisRepair(String key) {
        pendingRedisRepairs.invalidate(key);
    }

    /**
     * 批量删除失败时，为每个 key 都记录一个 INVALIDATE 待修复操作。
     */
    private void rememberPendingInvalidate(@Nullable Collection<String> keys) {
        if (keys == null) {
            return;
        }
        keys.forEach(key -> rememberPendingRedisRepair(
                key,
                PendingRedisOperation.invalidate(() -> distributedCache.delete(key))
        ));
    }

    /**
     * 记录 Redis 降级事件。
     * 非 0 说明当前服务已经在跳过 Redis 或 Redis 正在报错，需要重点关注。
     */
    private void recordDegradation(String operation) {
        if (degradationCounters != null) {
            Counter counter = degradationCounters.get(operation);
            if (counter != null) {
                counter.increment();
            }
        }
    }

    /**
     * 待修复操作类型。
     */
    private enum PendingOperationType {
        /**
         * 写入失败，Redis 中可能还是旧值。
         */
        UPSERT,

        /**
         * 删除失败，Redis 中可能残留旧值。
         */
        INVALIDATE
    }

    /**
     * 待修复操作描述。
     * <p>
     * 这里只保存恢复 Redis 正确状态所需的最小信息：
     * 1. 类型
     * 2. 新值（仅 UPSERT 需要）
     * 3. 回放动作
     */
    private static final class PendingRedisOperation {

        private final PendingOperationType type;
        private final Object value;
        private final Runnable replayAction;

        private PendingRedisOperation(PendingOperationType type,
                                      @Nullable Object value,
                                      Runnable replayAction) {
            this.type = type;
            this.value = value;
            this.replayAction = replayAction;
        }

        /**
         * 构造待补写操作。
         */
        private static PendingRedisOperation upsert(Object value, Runnable replayAction) {
            return new PendingRedisOperation(PendingOperationType.UPSERT, value, replayAction);
        }

        /**
         * 构造待删除操作。
         */
        private static PendingRedisOperation invalidate(Runnable replayAction) {
            return new PendingRedisOperation(PendingOperationType.INVALIDATE, null, replayAction);
        }
    }
}
