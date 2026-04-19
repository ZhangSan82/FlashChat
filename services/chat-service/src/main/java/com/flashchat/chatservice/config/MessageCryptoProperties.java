package com.flashchat.chatservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息正文字段加密配置。
 *
 * <p>本轮只保护 MySQL 中的消息正文，不覆盖 Redis Stream、Redis 窗口缓存、
 * 附件元数据等其它链路，这是当前阶段刻意保留的边界。</p>
 */
@Data
@ConfigurationProperties(prefix = "flashchat.message.crypto")
public class MessageCryptoProperties {

    /**
     * 是否开启消息正文加密写入。
     *
     * <p>开启后，新写入 MySQL 的消息会落到密文字段，而不是旧的明文 {@code content} 列。</p>
     */
    private boolean enabled = false;

    /**
     * 消息正文使用的 JCE 算法。
     */
    private String algorithm = "AES/GCM/NoPadding";

    /**
     * 当前写入版本对应的 AES 密钥，使用 Base64 编码。
     */
    private String key;

    /**
     * 新密文写入时记录到 {@code key_version} 的版本号。
     */
    private int keyVersion = 1;

    /**
     * 只读密钥环。
     *
     * <p>格式为 {@code version:base64,version:base64}。当前写入密钥仍然以
     * {@link #key} + {@link #keyVersion} 为准；如果 keyring 里存在相同版本，
     * 也会被当前写入配置覆盖，避免当前写路径存在双重来源。</p>
     */
    private String keyring;

    /**
     * AES-GCM 随机 IV 长度，12 字节是标准推荐值。
     */
    private int ivLengthBytes = 12;

    /**
     * AES-GCM 认证标签长度。
     */
    private int tagLengthBits = 128;

    /**
     * 控制历史明文数据如何回填到密文字段。
     */
    private BackfillProperties backfill = new BackfillProperties();

    @Data
    public static class BackfillProperties {

        /**
         * 是否开启定时回填任务。
         */
        private boolean enabled = false;

        /**
         * 应用启动后是否先执行一轮回填。
         */
        private boolean runOnStartup = false;

        /**
         * 单批最多扫描多少条历史消息。
         */
        private int batchSize = 200;

        /**
         * 一次调度最多执行多少个批次。
         */
        private int maxBatchesPerRun = 20;

        /**
         * 两批之间的休眠时间，用来避免持续压数据库。
         */
        private long batchSleepMs = 100;

        /**
         * 定时回填任务的固定间隔。
         */
        private long fixedDelayMs = 30000;

        /**
         * 定时任务首次执行前的延迟。
         */
        private long initialDelayMs = 30000;

        /**
         * 抢占分布式锁时的等待时间。
         */
        private long lockWaitSeconds = 0;

        /**
         * 分布式锁的租约时间。
         */
        private long lockLeaseSeconds = 600;
    }
}
