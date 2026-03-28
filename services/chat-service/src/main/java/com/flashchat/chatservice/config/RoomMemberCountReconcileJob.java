package com.flashchat.chatservice.config;

import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.dao.mapper.RoomMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * currentMembers 对账定时任务
 * 定期比对 t_room.current_members 与 COUNT(t_room_member WHERE status=ACTIVE)，
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomMemberCountReconcileJob {

    private final RoomMapper roomMapper;
    private final RoomMemberMapper roomMemberMapper;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "flashchat:job:member-count-reconcile";
    private static final int BATCH_SIZE = 200;

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void reconcile() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            return;
        }

        try {
            doReconcile();
        } catch (Exception e) {
            log.error("[对账任务] 执行异常", e);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("[对账任务] 释放锁异常", e);
            }
        }
    }

    private void doReconcile() {
        // 1. 查所有非关闭的房间
        List<RoomDO> rooms = roomMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RoomDO>()
                        .ne(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                        .select(RoomDO::getRoomId, RoomDO::getCurrentMembers)
                        .last("LIMIT " + BATCH_SIZE)
        );

        if (rooms == null || rooms.isEmpty()) {
            log.debug("[对账任务] 无活跃房间，跳过");
            return;
        }
        if (rooms.size() >= BATCH_SIZE) {
            log.warn("[对账任务] 活跃房间数达到批次上限 {}，可能有房间未被对账", BATCH_SIZE);
        }
        List<String> roomIds = rooms.stream()
                .map(RoomDO::getRoomId)
                .toList();
        // 2. DB 聚合查询
        List<Map<String, Object>> countMaps = roomMemberMapper.countActiveMembersByRoomIds(roomIds);
        Map<String, Integer> actualCountMap = new java.util.HashMap<>();
        for (Map<String, Object> map : countMaps) {
            String roomId = (String) map.get("room_id");
            Number cnt = (Number) map.get("cnt");
            actualCountMap.put(roomId, cnt.intValue());
        }
        // 3. 比对并校正
        int correctedCount = 0;
        for (RoomDO room : rooms) {
            int dbCount = room.getCurrentMembers() != null ? room.getCurrentMembers() : 0;
            int actualCount = actualCountMap.getOrDefault(room.getRoomId(), 0);

            if (dbCount != actualCount) {
                roomMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RoomDO>()
                                .eq(RoomDO::getRoomId, room.getRoomId())
                                .set(RoomDO::getCurrentMembers, actualCount)
                );

                log.warn("[对账-校正] room={}, current_members: {} → {}",
                        room.getRoomId(), dbCount, actualCount);
                correctedCount++;
            }
        }

        if (correctedCount > 0) {
            log.info("[对账任务] 完成，校正 {} 个房间", correctedCount);
        } else {
            log.debug("[对账任务] 完成，全部一致");
        }
    }
}
