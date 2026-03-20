package com.flashchat.chatservice.config;

/**
 * Redis Stream 消息持久化 — 常量配置
 * 架构：Producer（XADD）→ Redis Stream → Consumer（攒批 INSERT IGNORE）→ MySQL
 * 为什么用常量类而不是 @ConfigurationProperties：
 * 这些值一旦上线基本不会变，放 yaml 反而增加配置复杂度
 * 如果未来需要动态调整，再迁移到 yaml
 */
public final class MessageStreamConfig {

    private MessageStreamConfig() {
    }

    // ==================== Redis Stream 标识 ====================

    /** Stream Key */
    public static final String STREAM_KEY = "flashchat:msg:persist:stream";

    /** Consumer Group 名称 */
    public static final String GROUP_NAME = "flashchat-msg-group";

    /** Consumer 名称（单实例部署，固定名称即可） */
    public static final String CONSUMER_NAME = "consumer-1";

    /** Stream 消息体的 field 名称 */
    public static final String FIELD_PAYLOAD = "payload";

    // ==================== 攒批参数 ====================

    /**
     * 攒批大小
     * 每次 XREADGROUP 最多拉取的条数，也是触发 flush 的数量阈值
     */
    public static final int BATCH_SIZE = 50;

    /**
     * BLOCK 超时（毫秒）
     * 兼做攒批时间窗口：
     * - 高峰期：buffer 快速填满 BATCH_SIZE 条，不会等到超时
     * - 低峰期：200ms 超时后 flush 残留的几条消息
     */
    public static final long BLOCK_TIMEOUT_MS = 200;

    // ==================== Stream 维护 ====================

    /**
     * Stream 最大保留长度（近似裁剪）
     * 在 XADD 时使用：XADD key MAXLEN ~ 10000 * payload {json}
     * Redis 在每次写入时自动做渐进式裁剪，无需 Consumer 端额外 XTRIM
     * 10000 条 × 平均 200 字节/条 ≈ 2MB，内存可控
     */
    public static final long STREAM_MAX_LEN = 10000;

    // ==================== 容错参数 ====================

    /**
     * flush 失败后的重试等待时间（毫秒）
     * MySQL 宕机时避免疯狂重试，留出恢复窗口
     */
    public static final long FLUSH_RETRY_DELAY_MS = 2000;
}
