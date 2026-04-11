package com.flashchat.chatservice.service.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 RoomSerialLock 重构为 ReentrantLock[] + tryLock(timeout) 后的核心语义:
 *   1) 同一 roomId 命中同一条 stripe,不同线程严格串行
 *   2) 临界区内任务提交顺序与 seq 分配顺序完全一致(stripe 锁的正确性)
 *   3) 超时机制生效:锁被长时间持有时,等待方在超时后立即失败,不会无限阻塞
 *   4) AutoCloseable 协议:try-with-resources 退出后锁被释放,后续 acquire 可以立刻拿到
 */
class RoomSerialLockTest {

    private final RoomSerialLock lock = new RoomSerialLock();

    /**
     * 同一 roomId 多次调用必须命中同一条 stripe。
     * 用 acquire → close → acquire 的方式验证第二次不会卡住(证明 close 释放成功),
     * 且两次拿到的底层 ReentrantLock 是同一把(通过 stripeFor 包级可见 API 验证)。
     */
    @Test
    @DisplayName("同一 roomId 命中同一条 stripe")
    void sameRoomIdHitsSameStripe() {
        assertSame(lock.stripeFor("room-42"), lock.stripeFor("room-42"));
        try (RoomSerialLock.Handle ignored = lock.acquire("room-42")) {
            // 持锁,验证可正常进入
        }
        // close 后再次 acquire 必须不超时,证明 handle 正确释放
        try (RoomSerialLock.Handle ignored = lock.acquire("room-42", 200)) {
            assertThat(lock.stripeFor("room-42").isLocked()).isTrue();
        }
        assertThat(lock.stripeFor("room-42").isLocked()).isFalse();
    }

    /**
     * 分布均匀性 smoke test:相邻 roomId 通常落在不同 stripe。
     * 混淆后的 floorMod 仍然保证至少一半的样本分到不同 stripe。
     */
    @Test
    @DisplayName("相邻 roomId 落在不同 stripe 的比例 >= 50%")
    void differentRoomsUsuallyGetDifferentStripes() {
        int distinct = 0;
        var base = lock.stripeFor("room-0");
        for (int i = 1; i < 32; i++) {
            if (lock.stripeFor("room-" + i) != base) {
                distinct++;
            }
        }
        assertTrue(distinct >= 16, "Expected at least half of 32 rooms on different stripes, got " + distinct);
    }

    /**
     * 核心正确性测试:模拟 ChatServiceImpl.sendMsg 的临界区
     *   1. msgSeqId = counter.incrementAndGet()  (Redis INCR 的模拟)
     *   2. 短暂自旋模拟 XADD RTT
     *   3. submitOrder.add(msgSeqId)             (mailbox.submit 的模拟)
     * 若无锁保护,步骤 1 和步骤 3 之间会被另一个线程的步骤 1 插入,
     * 导致 submitOrder 与 seq 分配顺序不一致。
     * 加锁后,这 16×100 个任务的 submitOrder 必须严格等于 [1..1600]。
     */
    @Test
    @DisplayName("临界区串行化 — submit 顺序严格等于 seq 分配顺序")
    void serializeCriticalSectionPerRoom() throws Exception {
        int threadCount = 16;
        int tasksPerThread = 100;
        String roomId = "room-hot";
        AtomicLong seq = new AtomicLong(0);
        List<Long> submitOrder = Collections.synchronizedList(new ArrayList<>(threadCount * tasksPerThread));

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < tasksPerThread; i++) {
                            try (RoomSerialLock.Handle ignored = lock.acquire(roomId)) {
                                long id = seq.incrementAndGet();
                                // 模拟 XADD 耗时,放大竞态窗口
                                for (int spin = 0; spin < 50; spin++) {
                                    Math.sqrt(spin);
                                }
                                submitOrder.add(id);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threadCount * tasksPerThread, submitOrder.size());
        for (int i = 0; i < submitOrder.size(); i++) {
            assertEquals(i + 1L, submitOrder.get(i),
                    "submit 顺序必须严格等于 seq 分配顺序,位置 " + i + " 发生错位");
        }
    }

    /**
     * 超时机制:当 stripe 被其他线程长时间持有时,
     * 等待方必须在给定 timeoutMs 内抛 StripeLockTimeoutException,
     * 不能无限阻塞。这是这次 refactor 的核心收益 — 防止 Redis/XADD 抖动引发的
     * 跨房间级联雪崩。
     */
    @Test
    @DisplayName("锁被长时间持有时 acquire 必须在 timeoutMs 内快速失败")
    void acquireShouldTimeOutWhenLockHeldByOtherThread() throws Exception {
        String roomId = "room-jam";
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try (RoomSerialLock.Handle ignored = lock.acquire(roomId)) {
                holderAcquired.countDown();
                try {
                    releaseHolder.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "holder");
        holder.start();

        try {
            assertTrue(holderAcquired.await(1, TimeUnit.SECONDS));

            long t0 = System.nanoTime();
            RoomSerialLock.StripeLockTimeoutException ex = assertThrows(
                    RoomSerialLock.StripeLockTimeoutException.class,
                    () -> lock.acquire(roomId, 100));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertThat(ex.getMessage()).contains(roomId);
            // 允许一点调度抖动,但绝不应超过 timeoutMs 的 5 倍
            assertThat(elapsedMs)
                    .as("acquire 必须在 ~timeoutMs 内快速失败")
                    .isBetween(80L, 500L);
        } finally {
            releaseHolder.countDown();
            holder.join(1000);
        }
    }

    /**
     * 被阻塞的等待线程在被中断时必须立刻抛 StripeLockInterruptedException 而不是
     * 卡在 tryLock 里。这是线程池 shutdown 时的平滑下线保证 —
     * 裸 synchronized 做不到这个语义。
     */
    @Test
    @DisplayName("等待期间线程中断 — 必须立即抛 StripeLockInterruptedException")
    void interruptedWaiterShouldAbort() throws Exception {
        String roomId = "room-interrupt";
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try (RoomSerialLock.Handle ignored = lock.acquire(roomId)) {
                holderAcquired.countDown();
                try {
                    releaseHolder.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "holder");
        holder.start();

        try {
            assertTrue(holderAcquired.await(1, TimeUnit.SECONDS));

            Thread waiter = new Thread(() -> assertThrows(
                    RoomSerialLock.StripeLockInterruptedException.class,
                    () -> lock.acquire(roomId, 60_000)), "waiter");
            waiter.start();

            // 给 waiter 足够时间进入 tryLock 阻塞,再打断它
            Thread.sleep(50);
            waiter.interrupt();
            waiter.join(1000);
            assertThat(waiter.isAlive()).isFalse();
        } finally {
            releaseHolder.countDown();
            holder.join(1000);
        }
    }
}
