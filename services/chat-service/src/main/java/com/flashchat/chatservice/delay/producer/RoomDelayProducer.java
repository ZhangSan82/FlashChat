package com.flashchat.chatservice.delay.producer;

import com.flashchat.chatservice.delay.RoomDelayEvent;
import com.flashchat.chatservice.dto.enums.RoomDelayEventTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RDelayedQueue;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * 房间延时任务生产者
 * 创建房间 / 延期房间 时投递 3 个延时事件：
 *   ① expireTime - 5min  → EXPIRING_SOON  即将到期提醒
 *   ② expireTime         → EXPIRED        进入宽限期
 *   ③ expireTime + 5min  → GRACE_END      正式关闭
 * 版本号机制：
 *   每次投递都带上当前 expireVersion
 *   消费时比对版本号，不匹配则跳过（旧任务自动作废）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomDelayProducer {

    private final RDelayedQueue<RoomDelayEvent> roomDelayedQueue;

    /** 即将到期提醒：到期前 5 分钟 */
    private static final long EXPIRING_SOON_BEFORE_MINUTES = 5;

    /** 宽限期时长：到期后 5 分钟 */
    private static final long GRACE_PERIOD_MINUTES = 5;

    /**
     * 投递房间到期延时事件
     * 创建房间和延期房间都调用此方法
     * 延期时旧任务不取消，靠 version 机制自动作废
     *
     * @param roomId        房间业务 ID
     * @param expireTime    房间预计到期时间
     * @param expireVersion 当前到期版本号
     */
    public void submitRoomExpireEvents(String roomId, LocalDateTime expireTime, int expireVersion) {
        if (expireTime == null) {
            log.debug("[延时任务跳过] room={}, 广场房间永不到期", roomId);
            return;
        }
        long expireMs = toEpochMilli(expireTime);
        long nowMs = System.currentTimeMillis();

        //即将到期提醒（到期前 5 分钟）
        long expiringSoonMs = expireMs - TimeUnit.MINUTES.toMillis(EXPIRING_SOON_BEFORE_MINUTES);
        if (expiringSoonMs > nowMs) {
            offer(buildEvent(roomId, RoomDelayEventTypeEnum.EXPIRING_SOON, expireVersion, expiringSoonMs),
                    expiringSoonMs - nowMs);
        } else {
            log.info("[延时任务跳过] room={}, EXPIRING_SOON 时间已过（房间时长不足 {} 分钟）",
                    roomId, EXPIRING_SOON_BEFORE_MINUTES);
        }

        //  到期 → 进入宽限期
        if (expireMs > nowMs) {
            offer(buildEvent(roomId, RoomDelayEventTypeEnum.EXPIRED, expireVersion, expireMs),
                    expireMs - nowMs);
        } else {
            log.warn("[延时任务跳过] room={}, EXPIRED 时间已过", roomId);
        }

        // ③ 宽限期结束 → 正式关闭（到期后 5 分钟）
        long graceEndMs = expireMs + TimeUnit.MINUTES.toMillis(GRACE_PERIOD_MINUTES);
        if (graceEndMs > nowMs) {
            offer(buildEvent(roomId, RoomDelayEventTypeEnum.GRACE_END, expireVersion, graceEndMs),
                    graceEndMs - nowMs);
        } else {
            log.warn("[延时任务跳过] room={}, GRACE_END 时间已过", roomId);
        }

        log.info("[延时任务投递] room={}, version={}, expireTime={}, 提醒={}min前, 宽限={}min",
                roomId, expireVersion, expireTime, EXPIRING_SOON_BEFORE_MINUTES, GRACE_PERIOD_MINUTES);
    }

    /**
     * 投递单个延时任务
     * 失败不抛异常，由兜底定时任务保障
     */
    private void offer(RoomDelayEvent event, long delayMs) {
        try {
            roomDelayedQueue.offer(event, delayMs, TimeUnit.MILLISECONDS);
            log.debug("[延时任务] room={}, type={}, version={}, delay={}s",
                    event.getRoomId(), event.getEventType().getDesc(),
                    event.getExpireVersion(),
                    TimeUnit.MILLISECONDS.toSeconds(delayMs));
        } catch (Exception e) {
            log.error("[延时任务投递失败] room={}, type={}, 将由兜底任务兜底",
                    event.getRoomId(), event.getEventType().getDesc(), e);
        }
    }

    private RoomDelayEvent buildEvent(String roomId, RoomDelayEventTypeEnum eventType,
                                      int expireVersion, long triggerTime) {
        return RoomDelayEvent.builder()
                .roomId(roomId)
                .eventType(eventType)
                .expireVersion(expireVersion)
                .expectedTriggerTime(triggerTime)
                .build();
    }

    /**
     * 单独投递 GRACE_END 延时事件
     */
    public void submitGraceEndEvent(String roomId, LocalDateTime graceEndTime, Integer expireVersion) {
        long delayMs = java.time.Duration.between(LocalDateTime.now(), graceEndTime).toMillis();
        if (delayMs < 0) {
            delayMs = 0;
        }
        RoomDelayEvent event = RoomDelayEvent.builder()
                .roomId(roomId)
                .eventType(RoomDelayEventTypeEnum.GRACE_END)
                .expireVersion(expireVersion)
                .expectedTriggerTime(System.currentTimeMillis() + delayMs)
                .build();
        roomDelayedQueue.offer(event, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("[延时任务-单独投递 GRACE_END] room={}, graceEndTime={}, version={}, delay={}ms",
                roomId, graceEndTime, expireVersion, delayMs);
    }

    private long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}