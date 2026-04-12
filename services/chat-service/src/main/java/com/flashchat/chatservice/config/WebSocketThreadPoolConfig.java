package com.flashchat.chatservice.config;


import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * WebSocket 业务线程池配置
 * 为什么需要独立线程池：
 *   Netty Worker EventLoop 线程数 = CPU核心数，一个线程阻塞 = 该线程上所有连接卡住
 *   必须将阻塞操作提交到独立线程池，让 EventLoop 立即返回
 * 为什么不用 Netty 的 DefaultEventExecutorGroup：
 *   Spring ThreadPoolTaskExecutor 支持优雅关闭、JMX监控、与Spring生命周期集成
 */
@Slf4j
@Configuration
public class WebSocketThreadPoolConfig {

    @Bean("wsBusinessExecutor")
    public ThreadPoolTaskExecutor wsBusinessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ws-biz-");

        // 优雅关闭：等待队列中的任务执行完再关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);

        //拒绝策略：绝对不能用 CallerRunsPolicy！
        // 因为调用者是 EventLoop 线程，CallerRunsPolicy 会导致 EventLoop 执行阻塞操作
        // 回到原来的问题
        executor.setRejectedExecutionHandler(new WsRejectPolicy());

        executor.initialize();
        log.info("[WS线程池] 初始化完成, core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 256);
        return executor;
    }

    /**
     * 自定义拒绝策略
     * 队列满时丢弃任务并记录告警日志
     * 用户会因为收不到 LOGIN_SUCCESS 而超时，可以重新连接
     * 正常情况下不会触发（前面有队列容量预检查），这是最后的保底
     */
    @Slf4j
    static class WsRejectPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.error("[严重] ws业务线程池已满！activeCount={}, queueSize={}, completedTask={}",
                    executor.getActiveCount(),
                    executor.getQueue().size(),
                    executor.getCompletedTaskCount());
        }
    }
}