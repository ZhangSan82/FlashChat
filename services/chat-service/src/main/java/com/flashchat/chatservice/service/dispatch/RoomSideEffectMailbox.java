package com.flashchat.chatservice.service.dispatch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 房间副作用 Mailbox
 * 核心语义：
 *   1. roomId -> 固定 shard
 *   2. 每个 shard 单线程串行执行
 *   3. 同房间任务天然串行，避免窗口/广播/未读的执行顺序被打乱
 *
 * 为什么不用每房间一个线程：
 *   房间数量不稳定，线程模型不可控
 * 为什么用分片单线程：
 *   兼顾顺序性和资源上限，适合当前单机部署阶段
 */
@Slf4j
@Component
public class RoomSideEffectMailbox {

    private static final int DEFAULT_SHARD_COUNT = 8;
    private static final int DEFAULT_QUEUE_CAPACITY = 2048;
    private static final String DEFAULT_THREAD_NAME_PREFIX = "room-side-";

    private final ThreadPoolExecutor[] shardExecutors;
    private final Counter[] blockedSubmitCounters;

    @Autowired
    public RoomSideEffectMailbox(MeterRegistry meterRegistry) {
        this(DEFAULT_SHARD_COUNT, DEFAULT_QUEUE_CAPACITY, DEFAULT_THREAD_NAME_PREFIX, meterRegistry);
    }

    RoomSideEffectMailbox(int shardCount, int queueCapacity, String threadNamePrefix) {
        this(shardCount, queueCapacity, threadNamePrefix, null);
    }

    RoomSideEffectMailbox(int shardCount, int queueCapacity, String threadNamePrefix,
                          MeterRegistry meterRegistry) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.shardExecutors = new ThreadPoolExecutor[shardCount];
        this.blockedSubmitCounters = new Counter[shardCount];
        for (int i = 0; i < shardCount; i++) {
            int shard = i;
            // 拒绝策略不会被触发:submit 方法直接 put 到底层 queue 阻塞,
            // 保留 AbortPolicy 仅用于 executor.execute() 异常路径兜底。
            this.shardExecutors[i] = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    new NamedThreadFactory(threadNamePrefix + shard + "-"),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            if (meterRegistry != null) {
                Tags tags = Tags.of("shard", String.valueOf(shard));
                // backlog gauge:队列当前长度,体现反压压力
                Gauge.builder("chat.mailbox.backlog", shardExecutors[shard],
                                e -> e.getQueue().size())
                        .tags(tags)
                        .description("房间 side-effect mailbox 当前队列长度")
                        .register(meterRegistry);
                this.blockedSubmitCounters[shard] = Counter.builder("chat.mailbox.submit.blocked")
                        .tags(tags)
                        .description("队列满触发阻塞提交的次数")
                        .register(meterRegistry);
            }
        }
        log.info("[RoomMailbox] 初始化完成, shards={}, queueCapacity={}", shardCount, queueCapacity);
    }

    /**
     * 提交房间副作用任务
     * 反压模型:
     *   直接调用底层 queue.put() 阻塞提交,队列满时请求线程被阻塞直到有空位。
     *   不使用 CallerRunsPolicy,因为 CallerRunsPolicy 会让请求线程与 worker
     *   并行执行同一 shard 的任务,破坏"同 shard 单线程串行"的顺序承诺。
     *   阻塞提交对上游是自然反压,副作用不丢、顺序不乱、同房间顺序保持严格。
     * @return 任务完成 Future(调用方通常可忽略,仅测试/监控使用)
     */
    public CompletableFuture<Void> submit(String roomId, String taskName, Runnable task) {
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(task, "task must not be null");

        int shard = shardFor(roomId);
        CompletableFuture<Void> future = new CompletableFuture<>();
        BlockingQueue<Runnable> queue = shardExecutors[shard].getQueue();
        Runnable wrapped = () -> {
            try {
                task.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                log.error("[RoomMailbox任务失败] room={}, shard={}, task={}",
                        roomId, shard, taskName, t);
            }
        };
        try {
            // 阻塞提交:队列满时等待,保证"同 shard 单线程串行"语义不被破坏
            if (queue.remainingCapacity() == 0 && blockedSubmitCounters[shard] != null) {
                blockedSubmitCounters[shard].increment();
            }
            queue.put(wrapped);
            // 确保 worker 线程已启动(ThreadPoolExecutor 首次提交才拉起 worker,
            // 直接操作 queue 会绕过 prestart 逻辑)
            shardExecutors[shard].prestartCoreThread();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
            log.error("[RoomMailbox阻塞提交被中断] room={}, shard={}, task={}",
                    roomId, shard, taskName, e);
        }
        return future;
    }

    int shardFor(String roomId) {
        return Math.floorMod(roomId.hashCode(), shardExecutors.length);
    }

    @PreDestroy
    void shutdown() {
        for (ThreadPoolExecutor executor : shardExecutors) {
            executor.shutdown();
        }
        for (ThreadPoolExecutor executor : shardExecutors) {
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
