package com.flashchat.chatservice.service.dispatch;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomSideEffectMailboxTest {

    private final RoomSideEffectMailbox mailbox = new RoomSideEffectMailbox(2, 64, "test-room-side-");

    @AfterEach
    void tearDown() {
        mailbox.shutdown();
    }

    /**
     * 作用：验证同一房间提交到 mailbox 的副作用任务必须串行执行，
     * 后一个任务不能插队到前一个任务中间。
     * 预期结果：在第一个任务被刻意阻塞时，第二个任务不会提前运行；
     * 等第一个任务释放后，最终执行顺序必须严格等于 first-start -> first-end -> second。
     */
    @Test
    void submitShouldExecuteSameRoomTasksSequentially() throws Exception {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch unblockFirst = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);

        CompletableFuture<Void> first = mailbox.submit("room-1", "first-task", () -> {
            executionOrder.add("first-start");
            firstStarted.countDown();
            try {
                unblockFirst.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executionOrder.add("first-end");
        });

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        CompletableFuture<Void> second = mailbox.submit("room-1", "second-task", () -> {
            executionOrder.add("second");
            secondRan.countDown();
        });

        assertFalse(secondRan.await(200, TimeUnit.MILLISECONDS));

        unblockFirst.countDown();

        CompletableFuture.allOf(first, second).get(1, TimeUnit.SECONDS);
        assertEquals(List.of("first-start", "first-end", "second"), executionOrder);
    }

    /**
     * 作用：验证 mailbox 支持自定义 shard 数，并且 roomId 仍然会稳定映射到固定 shard。
     * 预期结果：同一个 roomId 多次计算 shard 一致，且 shard 下标不会越界。
     */
    @Test
    void shardCountShouldHonorCustomValueAndKeepStableMapping() {
        RoomSideEffectMailbox customMailbox = new RoomSideEffectMailbox(8, 64, "test-room-side-");
        try {
            assertEquals(8, customMailbox.shardCount());

            int shardA1 = customMailbox.shardFor("room-A");
            int shardA2 = customMailbox.shardFor("room-A");
            int shardB = customMailbox.shardFor("room-B");

            assertEquals(shardA1, shardA2);
            assertTrue(shardA1 >= 0 && shardA1 < customMailbox.shardCount());
            assertTrue(shardB >= 0 && shardB < customMailbox.shardCount());

            HashSet<Integer> shards = new HashSet<>();
            for (int index = 0; index < 32; index += 1) {
                shards.add(customMailbox.shardFor("room-" + index));
            }
            assertTrue(shards.size() > 1);
        } finally {
            customMailbox.shutdown();
        }
    }

    @Test
    void submitShouldRecordQueueWaitDuration() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RoomSideEffectMailbox customMailbox = new RoomSideEffectMailbox(1, 64, "test-room-side-", meterRegistry);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try {
            CompletableFuture<Void> first = customMailbox.submit("room-queue", "first-task", () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> second = customMailbox.submit("room-queue", "second-task", () -> { });

            Thread.sleep(150);
            assertFalse(second.isDone());

            releaseFirst.countDown();
            CompletableFuture.allOf(first, second).get(1, TimeUnit.SECONDS);

            Timer queueWaitTimer = meterRegistry.get("chat.mailbox.queue.wait.duration")
                    .tag("shard", "0")
                    .timer();
            assertEquals(2L, queueWaitTimer.count());
            assertTrue(queueWaitTimer.max(TimeUnit.MILLISECONDS) >= 100.0);
        } finally {
            customMailbox.shutdown();
            meterRegistry.close();
        }
    }
}
