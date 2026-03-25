package com.flashchat.chatservice.websocket.manager;

import com.flashchat.chatservice.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 房间成员内存清理定时任务
 * 两种清理策略：
 *   A. 已关闭房间的残留数据 → 直接清理
 *      正常情况下 closeRoom 已清理干净，此策略处理并发导致的残留
 *      方式：一次 DB 查询拿到所有 CLOSED 房间 ID，和内存取交集<p>
 *   B. 活跃房间中的僵尸成员 → 按不活跃时间清理
 *      用户断线后成员关系保留在内存，永不回来就是泄漏
 *      条件：不活跃超过 4 小时 + 不在线 + 不是房主
 * 执行频率：每 5 分钟，启动后 1 分钟首次执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomMemberCleanupJob {

    private final RoomChannelManager roomChannelManager;
    private final RoomService roomService;

    /** 僵尸成员的不活跃阈值：4 小时 */
    private static final long STALE_THRESHOLD_MS = 4 * 60 * 60 * 1000L;

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void cleanup() {
        try {
            // 只取一次快照，两个策略共用
            Map<String, Map<Long, Long>> snapshot = roomChannelManager.getRoomMembersSnapshot();
            if (snapshot.isEmpty()) {
                log.debug("[内存清理] roomMembers 为空，跳过");
                return;
            }

            int closedRoomsCleaned = cleanupClosedRooms(snapshot);
            int staleMembersCleaned = cleanupStaleMembers(snapshot);

            if (closedRoomsCleaned > 0 || staleMembersCleaned > 0) {
                log.info("[内存清理] 清理已关闭房间 {} 个, 清理僵尸成员 {} 个",
                        closedRoomsCleaned, staleMembersCleaned);
            } else {
                log.debug("[内存清理] 无需清理");
            }
        } catch (Exception e) {
            log.error("[内存清理] 执行异常", e);
        }
    }

    /**
     * 策略 A：清理已关闭房间的残留数据
     * 一次 DB 查询拿到所有 CLOSED 房间 ID，和内存快照取交集
     */
    private int cleanupClosedRooms(Map<String, Map<Long, Long>> snapshot) {
        Set<String> closedRoomIds;
        try {
            closedRoomIds = roomService.listClosedRoomIds();
        } catch (Exception e) {
            log.error("[清理已关闭房间] 查询 DB 异常，跳过本轮", e);
            return 0;
        }

        int count = 0;
        for (String roomId : snapshot.keySet()) {
            if (closedRoomIds.contains(roomId)) {
                roomChannelManager.cleanupClosedRoom(roomId);
                count++;
                log.info("[清理已关闭房间] room={}", roomId);
            }
        }
        return count;
    }

    /**
     * 策略 B：清理活跃房间中的僵尸成员
     * 跳过条件：
     *   - lastActiveTime 在阈值内（还算活跃）
     *   - 用户当前在线（可能只是没发消息，在看别人聊）
     *   - 用户是房主（房主身份保留到房间关闭）
     *   - roomId 已在策略 A 中被清理（snapshot 里有但 roomMembers 里已没有）
     */
    private int cleanupStaleMembers(Map<String, Map<Long, Long>> snapshot) {
        long now = System.currentTimeMillis();
        int count = 0;

        for (Map.Entry<String, Map<Long, Long>> roomEntry : snapshot.entrySet()) {
            String roomId = roomEntry.getKey();

            // 跳过已被策略 A 清理的房间
            if (roomChannelManager.getMemberCount(roomId) == 0) continue;

            for (Map.Entry<Long, Long> memberEntry : roomEntry.getValue().entrySet()) {
                Long userId = memberEntry.getKey();
                long lastActive = memberEntry.getValue();

                // 跳过活跃的成员
                if (now - lastActive < STALE_THRESHOLD_MS) continue;

                // 跳过在线的成员
                if (roomChannelManager.isOnline(userId)) continue;

                // 跳过房主
                if (roomChannelManager.isHost(roomId, userId)) continue;

                // 执行清理
                roomChannelManager.removeStaleMember(roomId, userId);
                count++;
            }
        }
        return count;
    }
}