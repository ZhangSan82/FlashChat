package com.flashchat.chatservice.service.dispatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
}
