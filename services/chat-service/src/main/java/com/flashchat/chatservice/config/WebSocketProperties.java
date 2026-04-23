package com.flashchat.chatservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 本地压测相关配置。
 * 默认值保持现有线上/非 perf 行为，local-perf 可以按需覆盖。
 */
@Data
@ConfigurationProperties(prefix = "flashchat.websocket")
public class WebSocketProperties {

    /**
     * Netty WebSocket 独立端口。
     */
    private int port = 8090;

    /**
     * Netty boss 线程数。
     */
    private int bossThreads = 1;

    /**
     * Netty worker 线程数。
     */
    private int workerThreads = 2;

    /**
     * 握手/恢复房间成员等阻塞任务线程池 core 大小。
     */
    private int businessCorePoolSize = 2;

    /**
     * 握手/恢复房间成员等阻塞任务线程池 max 大小。
     */
    private int businessMaxPoolSize = 4;

    /**
     * 握手/恢复房间成员等阻塞任务线程池队列容量。
     */
    private int businessQueueCapacity = 256;

    /**
     * 业务线程空闲保活秒数。
     */
    private int businessKeepAliveSeconds = 60;

    /**
     * 当剩余队列容量低于该值时，提前拒绝新的 WS 握手。
     */
    private int queueCapacityThreshold = 10;
}
