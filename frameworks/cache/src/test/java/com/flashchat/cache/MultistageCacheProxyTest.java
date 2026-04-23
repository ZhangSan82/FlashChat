package com.flashchat.cache;

import com.flashchat.cache.config.LocalCacheManager;
import com.flashchat.cache.config.RedisDistributedProperties;
import com.flashchat.cache.core.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        doNothing().when(distributedCache).put(KEY, "fresh-value");

        boolean deleted = Boolean.TRUE.equals(multistageCacheProxy.delete(KEY));
        assertFalse(deleted);

        Thread.sleep(20);

        CacheLoader<String> loader = Mockito.mock(CacheLoader.class);
        when(loader.load()).thenReturn("fresh-value");

        String result = multistageCacheProxy.safeGet(KEY, String.class, loader, TIMEOUT);

        assertEquals("fresh-value", result);
        verify(loader).load();
        verify(distributedCache, never()).safeGet(eq(KEY), eq(String.class), any(), eq(TIMEOUT));
        verify(distributedCache).put(KEY, "fresh-value");
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
}
