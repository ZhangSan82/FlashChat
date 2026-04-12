package com.flashchat.chatservice.service.dispatch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
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
 * 反压策略(非阻塞快速降级):
 *   submit 采用 {@code executor.execute()},队列满或 executor 已 shutdown 时
 *   立即抛 RejectedExecutionException,被 submit() 捕获并返回失败 future。
 *   上游(MessageSideEffectService)负责记录计数 + 日志,不再阻塞请求线程。
 *   这是刻意的工程取舍:消息已通过 durable handoff 被系统可靠接住,
 *   副作用(窗口/广播/未读)允许短时丢失,由客户端 ACK + /chat/new 兜底。
 *   绝不能让"副作用队列满"把请求线程钉死在 stripe 锁上造成跨房间级联雪崩。
 *
 * 为什么不用每房间一个线程:
 *   房间数量不稳定,线程模型不可控
 * 为什么用分片单线程:
 *   兼顾顺序性和资源上限,适合当前单机部署阶段
 */
@Slf4j
@Component
public class RoomSideEffectMailbox {

    private static final int DEFAULT_SHARD_COUNT = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;
    private static final String DEFAULT_THREAD_NAME_PREFIX = "room-side-";

    private final ThreadPoolExecutor[] shardExecutors;
    private final Counter[] rejectedCounters;
    private final Timer[] taskDurationTimers;

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
        this.rejectedCounters = new Counter[shardCount];
        this.taskDurationTimers = new Timer[shardCount];
        for (int i = 0; i < shardCount; i++) {
            int shard = i;
            // AbortPolicy: 队列满或 shutdown 时抛 RejectedExecutionException,
            // submit() 捕获后转成失败 future 返回给调用方,不阻塞请求线程。
            this.shardExecutors[i] = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    new NamedThreadFactory(threadNamePrefix + shard + "-"),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            // 预启动 core worker,避免首次 execute 延迟
            this.shardExecutors[i].prestartCoreThread();
            if (meterRegistry != null) {
                Tags tags = Tags.of("shard", String.valueOf(shard));
                // backlog gauge:队列当前长度,体现反压压力
                Gauge.builder("chat.mailbox.backlog", shardExecutors[shard],
                                e -> e.getQueue().size())
                        .tags(tags)
                        .description("房间 side-effect mailbox 当前队列长度")
                        .register(meterRegistry);
                this.rejectedCounters[shard] = Counter.builder("chat.mailbox.submit.rejected")
                        .tags(tags)
                        .description("队列满或已 shutdown 导致提交被拒绝的次数")
                        .register(meterRegistry);
                this.taskDurationTimers[shard] = Timer.builder("chat.mailbox.task.duration")
                        .tags(tags)
                        .description("房间副作用任务执行耗时")
                        .register(meterRegistry);
            }
        }
        log.info("[RoomMailbox] 初始化完成, shards={}, queueCapacity={}", shardCount, queueCapacity);
    }

    /**
     * 提交房间副作用任务(非阻塞)
     * 行为:
     *   1. executor 已 shutdown → 立即返回失败 future
     *   2. 队列满 → 立即返回失败 future,并累加 rejected counter
     *   3. 正常 → 进队列,执行完成后 future 正常 complete
     * 调用方必须处理失败 future(至少记录日志 + 计数),不应再依赖阻塞语义。
     */
    public CompletableFuture<Void> submit(String roomId, String taskName, Runnable task) {
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(task, "task must not be null");

        int shard = shardFor(roomId);
        ThreadPoolExecutor executor = shardExecutors[shard];
        CompletableFuture<Void> future = new CompletableFuture<>();

        // 快速失败: shutdown 竞态防护(避免任务入队后永不被执行)
        if (executor.isShutdown()) {
            incrementRejected(shard);
            future.completeExceptionally(
                    new RejectedExecutionException("mailbox shard " + shard + " shutdown"));
            return future;
        }

        Runnable wrapped = () -> {
            Timer.Sample sample = Timer.start();
            try {
                task.run();
                future.complete(null);
            } catch (Error err) {
                // JVM Error(OOM/StackOverflow 等)必须上抛给 UncaughtExceptionHandler,
                // 不能吞进 future,否则会掩盖进程级不可恢复状态。
                future.completeExceptionally(err);
                throw err;
            } catch (Throwable t) {
                future.completeExceptionally(t);
                log.error("[RoomMailbox任务失败] room={}, shard={}, task={}",
                        roomId, shard, taskName, t);
            } finally {
                if (taskDurationTimers[shard] != null) {
                    sample.stop(taskDurationTimers[shard]);
                }
            }
        };

        try {
            executor.execute(wrapped);
        } catch (RejectedExecutionException rex) {
            incrementRejected(shard);
            future.completeExceptionally(rex);
            log.warn("[RoomMailbox提交被拒绝] room={}, shard={}, task={}, backlog={}, remaining={}",
                    roomId, shard, taskName,
                    executor.getQueue().size(),
                    executor.getQueue().remainingCapacity());
        }
        return future;
    }

    int shardFor(String roomId) {
        // 做一次混淆以降低连号 roomId 的 hash 碰撞(String.hashCode 对短连号字符串分布较差)
        int h = roomId.hashCode();
        h = h ^ (h >>> 16);
        return Math.floorMod(h, shardExecutors.length);
    }

    private void incrementRejected(int shard) {
        if (rejectedCounters[shard] != null) {
            rejectedCounters[shard].increment();
        }
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
            // Error 场景下让 JVM 默认 UEH 处理(打印栈 + 线程退出),
            // 由于 shard 是单线程 executor,worker 退出会触发 ThreadPoolExecutor 重建新 worker。
            return thread;
        }
    }
}
