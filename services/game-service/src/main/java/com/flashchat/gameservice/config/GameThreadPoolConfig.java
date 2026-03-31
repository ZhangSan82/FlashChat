package com.flashchat.gameservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 游戏模块线程池配置
 * <p>
 * 两个独立线程池，互不干扰：
 * <ul>
 *   <li>gameTimerExecutor — 发言超时、投票超时、掉线重连定时任务</li>
 *   <li>aiPlayerExecutor — AI 发言/投票的 LLM API 调用（P1 阶段添加）</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableAsync
public class GameThreadPoolConfig {

    /**
     * 游戏定时器线程池
     */
    @Bean("gameTimerExecutor")
    public ScheduledExecutorService gameTimerExecutor() {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("game-timer-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        log.info("[游戏定时器线程池] 初始化完成, coreSize=2");
        return executor;
    }
}