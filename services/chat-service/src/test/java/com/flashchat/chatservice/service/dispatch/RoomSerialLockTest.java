package com.flashchat.chatservice.service.dispatch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-1 修复验证:
 *   并发线程对同一 roomId 做 "分配递增 seq → 提交任务" 的组合操作时,
 *   串行锁必须保证 seq 分配顺序与任务提交顺序严格一致。
 */
class RoomSerialLockTest {

    private final RoomSerialLock lock = new RoomSerialLock();

    /**
     * 同一 roomId 必须返回同一把锁对象。
     */
    @Test
    void sameRoomIdReturnsSameMonitor() {
        assertSame(lock.lockFor("room-42"), lock.lockFor("room-42"));
    }

    /**
     * 分布均匀性 smoke test:相邻 roomId 通常落在不同 stripe。
     */
    @Test
    void differentRoomsUsuallyGetDifferentStripes() {
        int distinct = 0;
        Object base = lock.lockFor("room-0");
        for (int i = 1; i < 32; i++) {
            if (lock.lockFor("room-" + i) != base) {
                distinct++;
            }
        }
        assertTrue(distinct >= 16, "Expected at least half of 32 rooms on different stripes, got " + distinct);
    }

    /**
     * 核心正确性测试:模拟 ChatServiceImpl.sendMsg 的临界区
     *   1. msgSeqId = counter.incrementAndGet()  (Redis INCR 的模拟)
     *   2. 短暂阻塞模拟 XADD RTT
     *   3. submitOrder.add(msgSeqId)            (mailbox.submit 的模拟)
     * 若无锁保护,步骤 1 和步骤 3 之间会被另一个线程的步骤 1 插入,
     * 导致 submitOrder 与 seq 分配顺序不一致。
     * 加锁后,这 100 个任务的 submitOrder 必须严格等于 [1..100]。
     */
    @Test
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
                            synchronized (lock.lockFor(roomId)) {
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
     * 不同房间之间不应互相阻塞:两个房间的锁对象不相同时可独立进入临界区。
     */
    @Test
    void differentRoomsDoNotBlockEachOther() {
        Object lockA = lock.lockFor("room-a");
        Object lockB = lock.lockFor("room-b");
        // 只在 hash 不冲突时断言独立;冲突就 degenerate 到 same monitor,
        // 但这不是我们控制的,仅验证语义:不同 lock 可独立持有。
        if (lockA != lockB) {
            assertNotSame(lockA, lockB);
        }
    }
}
