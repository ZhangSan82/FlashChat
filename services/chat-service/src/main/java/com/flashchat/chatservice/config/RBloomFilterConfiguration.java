package com.flashchat.chatservice.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RBloomFilterConfiguration {

    @Bean
    public RBloomFilter<String> flashChatRoomRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> cachePenetrationBloomFilter =
                redissonClient.getBloomFilter("flashChatRoomRegisterCachePenetrationBloomFilter");
        cachePenetrationBloomFilter.tryInit(100000L, 0.003);
        log.info("[BloomFilter] roomId filter initialized, expectedInsertions=100000, falseProbability=0.003");
        return cachePenetrationBloomFilter;
    }

    @Bean
    public RBloomFilter<String> flashChatAccountRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> cachePenetrationBloomFilter =
                redissonClient.getBloomFilter("flashChatAccountRegisterCachePenetrationBloomFilter");
        cachePenetrationBloomFilter.tryInit(100000L, 0.003);
        log.info("[BloomFilter] accountId filter initialized, expectedInsertions=100000, falseProbability=0.003");
        return cachePenetrationBloomFilter;
    }
}
