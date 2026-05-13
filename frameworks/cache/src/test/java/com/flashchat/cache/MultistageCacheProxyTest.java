package com.flashchat.cache;

import com.flashchat.cache.config.LocalCacheManager;
import com.flashchat.cache.config.RedisDistributedProperties;
import com.flashchat.cache.core.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultistageCacheProxyTest {

    private static final String KEY = "flashchat_account_1";
    private static final long TIMEOUT = 60L;

    private StringRedisTemplateProxy distributedCache;
    private MultistageCacheProxy multistageCacheProxy;

    @BeforeEach
    void setUp() {
        distributedCache = Mockito.mock(StringRedisTemplateProxy.class);

        RedisDistributedProperties.LocalCacheProperties localProps =
                new RedisDistributedProperties.LocalCacheProperties();
        LocalCacheManager localCacheManager = new LocalCacheManager(
                Caffeine.newBuilder().build(),
                Caffeine.newBuilder().build(),
                Caffeine.newBuilder().build(),
                localProps
        );

        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(1, 10, null);
        multistageCacheProxy = new MultistageCacheProxy(
                distributedCache,
                localCacheManager,
                circuitBreaker,
                null
        );
    }

    @Test
    void shouldBypassStaleRedisAfterDeleteFailureAndReloadFromLoader() throws Exception {
        when(distributedCache.delete(KEY))
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn(true);
        // INVALIDATE 重建会用业务声明的 TIMEOUT 回填 Redis(而非全局默认 TTL),
        // stub 必须与代理实际调用的 put 重载完全一致。
        doNothing().when(distributedCache).put(KEY, "fresh-value", TIMEOUT);

        boolean deleted = Boolean.TRUE.equals(multistageCacheProxy.delete(KEY));
        assertFalse(deleted);

        Thread.sleep(20);

        CacheLoader<String> loader = Mockito.mock(CacheLoader.class);
        when(loader.load()).thenReturn("fresh-value");

        String result = multistageCacheProxy.safeGet(KEY, String.class, loader, TIMEOUT);

        assertEquals("fresh-value", result);
        verify(loader).load();
        verify(distributedCache, never()).safeGet(eq(KEY), eq(String.class), any(), eq(TIMEOUT));
        verify(distributedCache).put(KEY, "fresh-value", TIMEOUT);
    }

    @Test
    void shouldReplayPendingPutAfterDistributedWriteFailure() throws Exception {
        doThrow(new RuntimeException("redis down"))
                .doNothing()
                .when(distributedCache)
                .put(KEY, "new-value", TIMEOUT);

        multistageCacheProxy.put(KEY, "new-value", TIMEOUT);
        multistageCacheProxy.invalidateLocal(KEY);

        Thread.sleep(20);

        CacheLoader<String> loader = Mockito.mock(CacheLoader.class);
        String result = multistageCacheProxy.safeGet(KEY, String.class, loader, TIMEOUT);

        assertEquals("new-value", result);
        verify(loader, never()).load();
        verify(distributedCache, times(2)).put(KEY, "new-value", TIMEOUT);
        verify(distributedCache, never()).safeGet(eq(KEY), eq(String.class), any(), eq(TIMEOUT));
    }

    @Test
    void shouldCoalesceDbFallbackWithLocalLockWhenRedisUnavailable() throws Exception {
        when(distributedCache.safeGet(eq(KEY), eq(String.class), any(), eq(TIMEOUT)))
                .thenThrow(new RuntimeException("redis down"));

        AtomicInteger loadCount = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CacheLoader<String> loader = () -> {
            loadCount.incrementAndGet();
            loaderEntered.countDown();
            sleepQuietly(50);
            return "fallback-value";
        };

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = IntStream.range(0, 8)
                    .mapToObj(i -> executor.submit(
                            () -> multistageCacheProxy.safeGet(KEY, String.class, loader, TIMEOUT)))
                    .toList();

            loaderEntered.await(1, TimeUnit.SECONDS);

            for (Future<String> future : futures) {
                assertEquals("fallback-value", future.get(1, TimeUnit.SECONDS));
            }
            assertEquals(1, loadCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldUseLocalLockFallbackAfterRedissonLockTimeout() throws Exception {
        when(distributedCache.safeGet(eq(KEY), eq(String.class), any(), eq(TIMEOUT)))
                .thenThrow(new CacheLockAcquireTimeoutException(KEY));
        when(distributedCache.get(KEY, String.class)).thenReturn(null);

        AtomicInteger loadCount = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CacheLoader<String> loader = () -> {
            loadCount.incrementAndGet();
            loaderEntered.countDown();
            sleepQuietly(50);
            return "lock-fallback-value";
        };

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = IntStream.range(0, 8)
                    .mapToObj(i -> executor.submit(
                            () -> multistageCacheProxy.safeGet(KEY, String.class, loader, TIMEOUT)))
                    .toList();

            loaderEntered.await(1, TimeUnit.SECONDS);

            for (Future<String> future : futures) {
                assertEquals("lock-fallback-value", future.get(1, TimeUnit.SECONDS));
            }
            assertEquals(1, loadCount.get());
            verify(distributedCache, times(1)).get(KEY, String.class);
        } finally {
            executor.shutdownNow();
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
