package com.flashchat.chatservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RoomSideEffectMailbox 配置。
 */
@Data
@ConfigurationProperties(prefix = "flashchat.mailbox")
public class MailboxProperties {

    /**
     * roomId -> shard 的分片数量。
     * 增大该值可以提升不同房间之间的并行度，但单房间仍然只会落到一个 shard 上串行执行。
     */
    private int shardCount = 4;

    /**
     * 每个 shard 的队列容量。
     */
    private int queueCapacity = 1024;

    /**
     * mailbox 工作线程名前缀。
     */
    private String threadNamePrefix = "room-side-";
}
