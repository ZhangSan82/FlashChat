package com.flashchat.cache;

/**
 * Redisson 分布式锁竞争失败的内部信号。
 * <p>
 * 在 {@link StringRedisTemplateProxy#safeGet} 内部，当 {@code tryLock} 超时
 * 或被 {@link InterruptedException} 中断、并且降级重试仍未命中缓存时，
 * 会抛出此异常上报给上层。
 * <p>
 * {@link MultistageCacheProxy} 在 {@code doGetWithDegradation} 中专门 catch 这个异常，
 * 然后通过 {@link LocalStripedLock} 走「锁失败 → 本地分片锁 → 锁内二次检查 → 必要时回源 DB」的兜底链路。
 * <p>
 * 这里之所以用异常而不是返回特殊值，是因为它穿越了多层 Redis 操作 API（包括内部的 tryLock 重试），
 * 通过异常可以一次性把控制权交还给最外层的多级缓存协调者，避免每个调用点都做特殊判断。
 * <p>
 * 包级可见性：仅作为缓存框架内部信号使用，业务层不应捕获。
 */
class CacheLockAcquireTimeoutException extends RuntimeException {

    /**
     * tryLock 超时（即未抛 InterruptedException、单纯没拿到锁）后构造。
     *
     * @param key 触发锁竞争失败的缓存 key
     */
    CacheLockAcquireTimeoutException(String key) {
        super("cache distributed lock acquire timeout, key=" + key);
    }

    /**
     * tryLock 等待过程中被中断时构造。
     * cause 保留底层 {@link InterruptedException} 信息，便于排查线程中断来源。
     *
     * @param key   触发锁竞争失败的缓存 key
     * @param cause 底层中断异常
     */
    CacheLockAcquireTimeoutException(String key, Throwable cause) {
        super("cache distributed lock acquire interrupted, key=" + key, cause);
    }
}
