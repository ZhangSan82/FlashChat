package com.flashchat.chatservice.delay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 房间到期兜底定时任务
 * 每分钟扫描一次 DB，处理延时队列遗漏的到期房间
 * 使用 Redisson 分布式锁，多实例部署时只有一个实例执行
 * 补偿逻辑：
 *   ACTIVE/EXPIRING + expire_time 已过        → doRoomExpired()  进入宽限期
 *   GRACE          + grace_end_time 已过       → doCloseRoom()   正式关闭
 *   WAITING        + expire_time 已过          → doCloseRoom()   直接关闭（无人使用的房间）
 * 正常情况下延时队列已经处理，此任务扫描不到任何记录
 * 只有延时队列故障时才会实际执行补偿
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomExpireCompensateJob {

    private final RoomMapper roomMapper;
    private final RoomService roomService;
    private final RedissonClient redissonClient;

    /** 分布式锁名称 */
    private static final String LOCK_KEY = "flashchat:job:room-expire-compensate";

    /** 每次最多处理的房间数，防止一次扫太多影响性能 */
    private static final int BATCH_SIZE = 100;

    /**
     * 每 60 秒执行一次
     * fixedDelay 保证上一次执行完才开始计时
     * 避免任务耗时较长导致重叠执行
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void compensate() {
        RLock lock = redissonClient.getLock(LOCK_KEY);

        // 尝试获取锁，拿不到说明其他实例在执行，直接跳过
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!acquired) {
            log.debug("[兜底任务] 未获取到锁，跳过本次执行");
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            int totalProcessed = 0;

            // 处理已到期但未进入宽限期的房间（ACTIVE / EXPIRING）
            totalProcessed += compensateExpired(now);

            // 处理宽限期已结束的房间（GRACE）
            totalProcessed += compensateGraceEnd(now);

            //  处理从未使用就到期的房间（WAITING）
            totalProcessed += compensateWaitingExpired(now);

            if (totalProcessed > 0) {
                log.info("[兜底任务] 本次补偿处理 {} 个房间", totalProcessed);
            } else {
                log.debug("[兜底任务] 无需补偿");
            }
        } catch (Exception e) {
            log.error("[兜底任务] 执行异常", e);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("[兜底任务] 释放锁异常", e);
            }
        }
    }

    /**
     * ① ACTIVE / EXPIRING + expire_time 已过 → 进入宽限期
     * SQL: SELECT * FROM t_room
     *      WHERE status IN (1, 2)
     *        AND expire_time <= NOW()
     *        AND del_flag = 0
     *      LIMIT 100
     */
    private int compensateExpired(LocalDateTime now) {
        List<RoomDO> rooms = roomMapper.selectList(
                new LambdaQueryWrapper<RoomDO>()
                        .in(RoomDO::getStatus,
                                RoomStatusEnum.ACTIVE.getCode(),
                                RoomStatusEnum.EXPIRING.getCode())
                        // expire_time <= now 自动排除 NULL（广场房间永不到期）
                        .le(RoomDO::getExpireTime, now)
                        .last("LIMIT " + BATCH_SIZE)
        );

        for (RoomDO room : rooms) {
            try {
                roomService.doRoomExpired(room.getRoomId());
                log.info("[兜底-到期补偿] room={}, 原状态={} → GRACE",
                        room.getRoomId(), room.getStatus());
            } catch (Exception e) {
                log.error("[兜底-到期补偿失败] room={}", room.getRoomId(), e);
            }
        }

        return rooms.size();
    }

    /**
     * ② GRACE + grace_end_time 已过 → 正式关闭
     * SQL: SELECT * FROM t_room
     *      WHERE status = 3
     *        AND grace_end_time <= NOW()
     *        AND del_flag = 0
     *      LIMIT 100
     */
    private int compensateGraceEnd(LocalDateTime now) {
        List<RoomDO> rooms = roomMapper.selectList(
                new LambdaQueryWrapper<RoomDO>()
                        .eq(RoomDO::getStatus, RoomStatusEnum.GRACE.getCode())
                        .le(RoomDO::getGraceEndTime, now)
                        .last("LIMIT " + BATCH_SIZE)
        );

        for (RoomDO room : rooms) {
            try {
                roomService.doCloseRoom(room.getRoomId());
                log.info("[兜底-宽限期补偿] room={} → CLOSED", room.getRoomId());
            } catch (Exception e) {
                log.error("[兜底-宽限期补偿失败] room={}", room.getRoomId(), e);
            }
        }

        return rooms.size();
    }

    /**
     * ③ WAITING + expire_time 已过 → 直接关闭
     * 场景：房主创建了房间但一直没人加入，已过期
     * SQL: SELECT * FROM t_room
     *      WHERE status = 0
     *        AND expire_time <= NOW()
     *        AND del_flag = 0
     *      LIMIT 100
     */
    private int compensateWaitingExpired(LocalDateTime now) {
        List<RoomDO> rooms = roomMapper.selectList(
                new LambdaQueryWrapper<RoomDO>()
                        .eq(RoomDO::getStatus, RoomStatusEnum.WAITING.getCode())
                        .le(RoomDO::getExpireTime, now)
                        .last("LIMIT " + BATCH_SIZE)
        );

        for (RoomDO room : rooms) {
            try {
                roomService.doCloseRoom(room.getRoomId());
                log.info("[兜底-等待超时补偿] room={} → CLOSED", room.getRoomId());
            } catch (Exception e) {
                log.error("[兜底-等待超时补偿失败] room={}", room.getRoomId(), e);
            }
        }

        return rooms.size();
    }
}