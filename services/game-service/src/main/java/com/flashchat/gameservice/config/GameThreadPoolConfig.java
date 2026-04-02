package com.flashchat.gameservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 游戏模块线程池配置。
 * <p>
 * 当前拆成两类线程池：
 * 1. gameTimerExecutor：发言超时、投票超时、掉线宽限等定时任务
 * 2. aiPlayerExecutor：AI 发言和 AI 投票的外部 HTTP 调用
 */
@Slf4j
@Configuration
@EnableAsync
public class GameThreadPoolConfig {

    private final AtomicInteger aiThreadCounter = new AtomicInteger(1);

    /**
     * 游戏定时器线程池。
     */
    @Bean("gameTimerExecutor")
    public ScheduledExecutorService gameTimerExecutor() {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("game-timer-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        });
        log.info("[游戏定时器线程池] 初始化完成 coreSize=2");
        return executor;
    }

    /**
     * AI 调用线程池。
     * <p>
     * AI 任务属于典型 IO 密集型任务，单独拆池可以避免和游戏主流程抢线程。
     * 队列满时退化为调用方线程执行，保证任务不会被静默丢弃。
     */
    @Bean("aiPlayerExecutor")
    public ExecutorService aiPlayerExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4,
                8,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("game-ai-" + aiThreadCounter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("[AI 线程池] 初始化完成 coreSize=4, maxSize=8, queueCapacity=32");
        return executor;
    }
}
