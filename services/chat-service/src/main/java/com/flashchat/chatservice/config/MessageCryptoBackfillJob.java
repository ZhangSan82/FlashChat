package com.flashchat.chatservice.config;

import com.flashchat.chatservice.service.crypto.MessageCryptoBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 消息正文历史回填的定时任务封装。
 *
 * <p>这里使用分布式锁，避免多个 chat-service 实例同时处理同一批迁移数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageCryptoBackfillJob {

    private static final String LOCK_KEY = "flashchat:job:message-crypto-backfill";

    private final MessageCryptoBackfillService messageCryptoBackfillService;
    private final MessageCryptoProperties messageCryptoProperties;
    private final RedissonClient redissonClient;

    @Scheduled(
            fixedDelayString = "${flashchat.message.crypto.backfill.fixed-delay-ms:30000}",
            initialDelayString = "${flashchat.message.crypto.backfill.initial-delay-ms:30000}"
    )
    public void scheduledBackfill() {
        if (!messageCryptoProperties.getBackfill().isEnabled()) {
            return;
        }
        runOnce("scheduled");
    }

    /**
     * 执行一次带分布式锁保护的回填，并返回本轮更新行数。
     */
    public int runOnce(String trigger) {
        MessageCryptoProperties.BackfillProperties backfill = messageCryptoProperties.getBackfill();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                    backfill.getLockWaitSeconds(),
                    backfill.getLockLeaseSeconds(),
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (!acquired) {
            log.debug("[message crypto backfill] trigger={}, lock busy", trigger);
            return 0;
        }

        try {
            int updated = messageCryptoBackfillService.backfillConfiguredRounds();
            if (updated > 0) {
                log.info("[message crypto backfill] trigger={}, updated={}", trigger, updated);
            } else {
                log.debug("[message crypto backfill] trigger={}, no legacy rows", trigger);
            }
            return updated;
        } catch (Exception e) {
            log.error("[message crypto backfill] trigger={} failed", trigger, e);
            return 0;
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("[message crypto backfill] release lock failed", e);
            }
        }
    }
}
