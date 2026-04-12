package com.flashchat.chatservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 异步与调度配置
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfiguration {

    /**
     * 统一维护调度器：承载所有 @Scheduled 低优先级维护任务。
     * <p>
     * 单线程顺序执行，保证维护任务之间不并发、不抢 CPU。
     * 覆盖 Spring 默认的 TaskScheduler，所有 @Scheduled 方法自动使用此调度器。
     * <p>
     * 承载任务清单：
     *   - RoomExpireCompensateJob（60s）
     *   - UnreadCleanupJob（每天 3:00 AM）
     *   - RoomMemberCountReconcileJob（5min）
     *   - RoomMemberCleanupJob（5min）
     *   - SensitiveWordReload（5min）
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("maintenance-");
        scheduler.setDaemon(true);
        scheduler.setErrorHandler(t ->
                org.slf4j.LoggerFactory.getLogger("MaintenanceScheduler")
                        .error("[维护任务异常] {}", t.getMessage(), t));
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}