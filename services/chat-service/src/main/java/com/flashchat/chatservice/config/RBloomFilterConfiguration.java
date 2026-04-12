package com.flashchat.chatservice.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 布隆过滤器配置
 */
@Configuration
@Slf4j
public class RBloomFilterConfiguration {

    /**
     * 存储roomID的过滤器
     */
    @Bean
    public RBloomFilter<String> flashChatRoomRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> cachePenetrationBloomFilter = redissonClient.getBloomFilter("flashChatRoomRegisterCachePenetrationBloomFilter");
        cachePenetrationBloomFilter.tryInit(50000L, 0.003);
        log.info("[布隆过滤器] roomId 过滤器初始化完成，预期容量=50000，误判率=0.003");
        return cachePenetrationBloomFilter;
    }

    /**
     * 存储账号ID的过滤器
     */
    @Bean
    public RBloomFilter<String> flashChatAccountRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> cachePenetrationBloomFilter = redissonClient.getBloomFilter("flashChatAccountRegisterCachePenetrationBloomFilter");
        cachePenetrationBloomFilter.tryInit(50000L, 0.003);
        log.info("[布隆过滤器] accountId 过滤器初始化完成，预期容量=50000，误判率=0.003");
        return cachePenetrationBloomFilter;
    }
}