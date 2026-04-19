package com.flashchat.chatservice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RBloomFilterConfigurationTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBloomFilter<String> roomBloomFilter;

    @Mock
    private RBloomFilter<String> accountBloomFilter;

    private final RBloomFilterConfiguration configuration = new RBloomFilterConfiguration();

    @Test
    void shouldInitializeRoomBloomFilterWithRestoredCapacity() {
        doReturn(roomBloomFilter).when(redissonClient)
                .getBloomFilter("flashChatRoomRegisterCachePenetrationBloomFilter");

        configuration.flashChatRoomRegisterCachePenetrationBloomFilter(redissonClient);

        verify(roomBloomFilter).tryInit(100000L, 0.003);
    }

    @Test
    void shouldInitializeAccountBloomFilterWithRestoredCapacity() {
        doReturn(accountBloomFilter).when(redissonClient)
                .getBloomFilter("flashChatAccountRegisterCachePenetrationBloomFilter");

        configuration.flashChatAccountRegisterCachePenetrationBloomFilter(redissonClient);

        verify(accountBloomFilter).tryInit(100000L, 0.003);
    }
}
