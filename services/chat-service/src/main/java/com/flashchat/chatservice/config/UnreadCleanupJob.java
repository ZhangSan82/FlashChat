package com.flashchat.chatservice.config;

import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.service.RoomMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 未读数清理定时任务
 * <p>
 * 每天凌晨 3 点执行，清理已失效的未读 Hash field
 * 处理主动清理遗漏的场景（Redis 异常、服务宕机等）
 * <p>
 * 使用 SCAN 游标式遍历（不用 KEYS，避免阻塞 Redis）
 * 每处理一批 key 后休眠，避免 Redis 和 DB 持续承压
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnreadCleanupJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final RoomMemberService roomMemberService;
    private final RedissonClient redissonClient;

    private static final String UNREAD_KEY_PREFIX = "flashchat:unread:";
    private static final String LOCK_KEY = "flashchat:job:unread-cleanup";

    /** 每处理 50 个 key 后休眠 */
    private static final int BATCH_SIZE = 50;
    private static final long BATCH_SLEEP_MS = 100;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            // leaseTime 600s：用户量大时任务可能跑较久
            acquired = lock.tryLock(0, 600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            log.debug("[未读清理] 未获取到锁，跳过");
            return;
        }

        try {
            int[] stats = doCleanup();
            if (stats[0] > 0 || stats[1] > 0) {
                log.info("[未读清理] 完成 | 处理 {} 个用户, 清理 {} 个无效field, 删除 {} 个空Hash",
                        stats[2], stats[0], stats[1]);
            } else {
                log.debug("[未读清理] 无需清理");
            }
        } catch (Exception e) {
            log.error("[未读清理] 执行异常", e);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("[未读清理] 释放锁异常", e);
            }
        }
    }

    /**
     * 游标式遍历 + 逐 key 处理
     * 边扫描边处理，不全量加载到内存
     *
     * @return [清理的field数, 删除的空Hash数, 处理的用户数]
     */
    private int[] doCleanup() {
        int fieldsRemoved = 0;
        int keysDeleted = 0;
        int usersProcessed = 0;

        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(UNREAD_KEY_PREFIX + "*")
                .count(BATCH_SIZE)
                .build();

        try (Cursor<String> cursor = stringRedisTemplate.scan(scanOptions)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                try {
                    int[] result = cleanSingleKey(key);
                    fieldsRemoved += result[0];
                    keysDeleted += result[1];
                    usersProcessed++;

                    // 批次控制：每处理 BATCH_SIZE 个 key 休眠一次
                    if (usersProcessed % BATCH_SIZE == 0) {
                        sleepQuietly(BATCH_SLEEP_MS);
                    }
                } catch (Exception e) {
                    log.error("[未读清理] 处理key异常: {}", key, e);
                }
            }
        } catch (Exception e) {
            log.error("[未读清理] SCAN异常", e);
        }

        return new int[]{fieldsRemoved, keysDeleted, usersProcessed};
    }

    /**
     * 清理单个用户的未读 Hash
     *
     * @return [清理的field数, 是否删除了整个key（0或1）]
     */
    private int[] cleanSingleKey(String key) {
        // 1. 从 key 中提取 accountId
        String accountIdStr = key.substring(UNREAD_KEY_PREFIX.length());
        Long accountId;
        try {
            accountId = Long.parseLong(accountIdStr);
        } catch (NumberFormatException e) {
            log.warn("[未读清理] key格式异常: {}", key);
            return new int[]{0, 0};
        }

        // 2. HGETALL 获取所有 field
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            stringRedisTemplate.delete(key);
            return new int[]{0, 1};
        }

        // 3. 查 DB：该用户所有活跃房间的 roomId 集合
        Set<String> activeRoomIds = getActiveRoomIds(accountId);
        if (activeRoomIds == null) {
            // 防止误删全部未读数据
            return new int[]{0, 0};
        }

        // 4. 收集需要清理的 field
        List<String> toRemove = new ArrayList<>();
        for (Object field : entries.keySet()) {
            String roomId = field.toString();
            if ("__init__".equals(roomId)) continue;
            if (!activeRoomIds.contains(roomId)) {
                toRemove.add(roomId);
            }
        }

        // 5. 批量 HDEL，一次网络往返
        int removed = 0;
        if (!toRemove.isEmpty()) {
            stringRedisTemplate.opsForHash().delete(key, toRemove.toArray());
            removed = toRemove.size();
        }

        // 6. 检查清理后是否为空
        Long remainingSize = stringRedisTemplate.opsForHash().size(key);
        if (remainingSize != null && remainingSize <= 1) {
            // 只剩 __init__ 标记或完全为空，删除整个 key
            boolean hasOnlyInit = remainingSize == 1
                    && stringRedisTemplate.opsForHash().hasKey(key, "__init__");
            if (remainingSize == 0 || hasOnlyInit) {
                stringRedisTemplate.delete(key);
                return new int[]{removed, 1};
            }
        }

        return new int[]{removed, 0};
    }

    /**
     * 查询用户的活跃房间 ID 集合
     * @return 活跃房间 ID 集合，null 表示查询失败（调用方应跳过该用户）
     */
    private Set<String> getActiveRoomIds(Long accountId) {
        try {
            List<RoomMemberDO> members = roomMemberService.lambdaQuery()
                    .eq(RoomMemberDO::getAccountId, accountId)
                    .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                    .select(RoomMemberDO::getRoomId)
                    .list();
            return members.stream()
                    .map(RoomMemberDO::getRoomId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            // 返回 null 表示查询失败，cleanSingleKey 中跳过该用户
            log.error("[未读清理] 查活跃房间失败, accountId={}, 跳过该用户", accountId, e);
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}