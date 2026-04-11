package com.flashchat.chatservice.service.dispatch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 房间级串行锁 (striped lock)
 * 用途:
 *   保证同一 roomId 在 "分配 msgSeqId → durable handoff → 提交 mailbox" 这段临界区内
 *   顺序执行,消除以下竞态:
 *     线程 A 拿到 msgSeqId=10 → 被 OS 调度打断
 *     线程 B 拿到 msgSeqId=11 → 先完成 mailbox.submit
 *     线程 A 后 submit
 *   → mailbox 中同一房间执行顺序 11 在前,广播顺序倒置,窗口 ZADD 顺序也反了。
 *
 * 设计说明:
 *   - 使用固定 64 条 stripe 的 ReentrantLock 数组,避免为每个 roomId 分配 Lock 造成泄漏
 *   - 相较于裸 synchronized,有三个关键收益:
 *     1) tryLock(timeoutMs) 可设等待上限 — 临界区里的 XADD 抖动不会无限拖死调用线程
 *     2) 线程池 shutdown 时 await 的线程可中断 — 平滑下线不被破坏
 *     3) chat.stripe.lock.wait.duration / chat.stripe.lock.timeout 可观测,
 *        stripe 竞争热点不再是黑盒
 *   - 通过 {@link Handle#close()} 释放,调用方使用 try-with-resources 保证成对解锁
 *   - hash 做一次 `h ^ (h >>> 16)` 混淆,降低短连号 roomId 的 stripe 碰撞
 */
@Slf4j
@Component
public class RoomSerialLock {

    private static final int STRIPES = 64;
    /** 默认等待上限:XADD 正常 ~1ms,3s 已属病态,再等没有意义,快失败让客户端重试。 */
    public static final long DEFAULT_WAIT_TIMEOUT_MS = 3000L;

    private final ReentrantLock[] locks;
    private final long defaultTimeoutMs;
    private final Timer waitTimer;
    private final Counter timeoutCounter;

    @Autowired
    public RoomSerialLock(MeterRegistry meterRegistry) {
        this(DEFAULT_WAIT_TIMEOUT_MS, meterRegistry);
    }

    /** 测试友好的无参构造:无 metrics、使用默认超时。生产走 {@link #RoomSerialLock(MeterRegistry)}。 */
    public RoomSerialLock() {
        this(DEFAULT_WAIT_TIMEOUT_MS, null);
    }

    RoomSerialLock(long defaultTimeoutMs, MeterRegistry meterRegistry) {
        if (defaultTimeoutMs <= 0) {
            throw new IllegalArgumentException("defaultTimeoutMs must be positive");
        }
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.locks = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.locks[i] = new ReentrantLock();
        }
        if (meterRegistry != null) {
            this.waitTimer = Timer.builder("chat.stripe.lock.wait.duration")
                    .description("房间 stripe 锁等待耗时 (从调用 acquire 到拿到锁)")
                    .register(meterRegistry);
            this.timeoutCounter = Counter.builder("chat.stripe.lock.timeout")
                    .description("房间 stripe 锁等待超时次数 — 非 0 即链路抖动信号")
                    .register(meterRegistry);
        } else {
            this.waitTimer = null;
            this.timeoutCounter = null;
        }
    }

    /**
     * 获取房间级串行锁,使用默认超时。
     * 调用方必须使用 try-with-resources 释放:
     * <pre>
     * try (RoomSerialLock.Handle ignored = roomSerialLock.acquire(roomId)) {
     *     // 临界区
     * }
     * </pre>
     *
     * @throws StripeLockTimeoutException  等待超过 timeoutMs 仍未拿到锁
     * @throws StripeLockInterruptedException 等待期间线程被中断
     */
    public Handle acquire(String roomId) {
        return acquire(roomId, defaultTimeoutMs);
    }

    public Handle acquire(String roomId, long timeoutMs) {
        ReentrantLock lock = stripeFor(roomId);
        long t0 = System.nanoTime();
        boolean acquired;
        try {
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StripeLockInterruptedException("stripe lock interrupted, room=" + roomId);
        }
        long elapsedNanos = System.nanoTime() - t0;
        if (waitTimer != null) {
            waitTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
        if (!acquired) {
            if (timeoutCounter != null) {
                timeoutCounter.increment();
            }
            log.warn("[StripeLock 超时] room={}, timeoutMs={}, queueLen={}",
                    roomId, timeoutMs, lock.getQueueLength());
            throw new StripeLockTimeoutException(
                    "stripe lock acquire timeout, room=" + roomId + ", waitMs=" + timeoutMs);
        }
        return new Handle(lock);
    }

    ReentrantLock stripeFor(String roomId) {
        int h = roomId.hashCode();
        h = h ^ (h >>> 16);
        return locks[Math.floorMod(h, STRIPES)];
    }

    /**
     * RAII 句柄:close() 保证释放锁。非线程安全 — 由拿到 handle 的线程独占使用。
     */
    public static final class Handle implements AutoCloseable {
        private final ReentrantLock lock;
        private boolean released;

        Handle(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                lock.unlock();
            }
        }
    }

    /** 等待超过上限未拿到 stripe 锁,由上层映射为 BUSY_ROOM 客户端错误。 */
    public static class StripeLockTimeoutException extends RuntimeException {
        public StripeLockTimeoutException(String message) {
            super(message);
        }
    }

    /** 等待期间线程被中断,通常发生在线程池 shutdown 时。 */
    public static class StripeLockInterruptedException extends RuntimeException {
        public StripeLockInterruptedException(String message) {
            super(message);
        }
    }
}
