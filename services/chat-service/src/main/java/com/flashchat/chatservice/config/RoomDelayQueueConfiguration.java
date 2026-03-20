package com.flashchat.chatservice.config;

import com.flashchat.chatservice.delay.RoomDelayEvent;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 房间到期延时队列配置
 * 通过 @Bean 注册 Redisson 数据结构，生产者和消费者各自注入
 */
@Slf4j
@Configuration
public class RoomDelayQueueConfiguration {

    private static final String QUEUE_NAME = "flashchat:room:delay:queue";

    @Bean
    public RBlockingQueue<RoomDelayEvent> roomDelayBlockingQueue(RedissonClient redissonClient) {
        RBlockingQueue<RoomDelayEvent> queue = redissonClient.getBlockingQueue(QUEUE_NAME);
        log.info("[延时队列] RBlockingQueue 初始化完成, name={}", QUEUE_NAME);
        return queue;
    }

    @Bean(destroyMethod = "")  //关闭时不调用 destroy()，保留 Redis 中的延时任务
    public RDelayedQueue<RoomDelayEvent> roomDelayedQueue(
            RBlockingQueue<RoomDelayEvent> roomDelayBlockingQueue,
            RedissonClient redissonClient) {
        RDelayedQueue<RoomDelayEvent> delayedQueue = redissonClient.getDelayedQueue(roomDelayBlockingQueue);
        log.info("[延时队列] RDelayedQueue 初始化完成");
        return delayedQueue;
    }
}