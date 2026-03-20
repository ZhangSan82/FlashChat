package com.flashchat.chatservice.delay.consumer;

import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.delay.RoomDelayEvent;
import com.flashchat.chatservice.service.RoomService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.springframework.stereotype.Component;

/**
 * 房间延时任务消费者
 * 独立守护线程持续 take()，到期事件自动触发
 * 两层幂等保护：
 *   1. 房间已关闭 → 跳过
 *   2. 版本号不匹配（房间已延期）→ 跳过
 * 消费失败不重新投递，由兜底定时任务保障
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomDelayConsumer {

    private final RBlockingQueue<RoomDelayEvent> roomDelayBlockingQueue;
    private final RoomService roomService;

    /** 控制消费循环退出 */
    private volatile boolean running = true;
    private Thread consumerThread;

    @PostConstruct
    public void start() {
        consumerThread = new Thread(this::consumeLoop, "room-delay-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("[延时队列消费者] 启动成功");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        log.info("[延时队列消费者] 已停止");
    }

    /**
     * 消费主循环
     * take() 是阻塞操作：
     *   队列为空时线程阻塞等待，不消耗 CPU
     *   有消息到期时立即返回
     *   应用关闭时通过 interrupt() 退出
     */
    private void consumeLoop() {
        while (running) {
            try {
                RoomDelayEvent event = roomDelayBlockingQueue.take();
                handleEvent(event);
            } catch (InterruptedException e) {
                if (!running) {
                    log.info("[延时队列消费者] 收到中断信号，退出消费循环");
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                log.error("[延时队列消费者] 消费异常，1 秒后重试", e);
                sleepQuietly(1000);
            }
        }
    }

    /**
     * 处理单个延时事件
     */
    private void handleEvent(RoomDelayEvent event) {
        String roomId = event.getRoomId();

        log.info("[延时任务触发] room={}, type={}, version={}, expectedTime={}",
                roomId, event.getEventType().getDesc(),
                event.getExpireVersion(), event.getExpectedTriggerTime());

        try {
            // ===== 幂等检查 =====
            RoomDO room = roomService.getRoomByRoomId(roomId);

            if (room == null) {
                log.warn("[延时任务-跳过] room={}, 房间不存在", roomId);
                return;
            }

            // 检查 1：房间已关闭
            if (room.getStatus() == RoomStatusEnum.CLOSED.getCode()) {
                log.info("[延时任务-跳过] room={}, 已关闭", roomId);
                return;
            }

            // 检查 2：版本号不匹配（房间已延期，旧任务作废）
            if (!event.getExpireVersion().equals(room.getExpireVersion())) {
                log.info("[延时任务-跳过] room={}, 版本不匹配 event.v={} room.v={}（房间已延期）",
                        roomId, event.getExpireVersion(), room.getExpireVersion());
                return;
            }

            // ===== 版本匹配，执行业务 =====
            switch (event.getEventType()) {
                case EXPIRING_SOON -> roomService.doRoomExpiringSoon(roomId);
                case EXPIRED -> roomService.doRoomExpired(roomId);
                case GRACE_END -> roomService.doCloseRoom(roomId);
            }

            log.info("[延时任务完成] room={}, type={}", roomId, event.getEventType().getDesc());

        } catch (Exception e) {
            // 消费失败不重新投递，由兜底定时任务扫描 DB 保障
            log.error("[延时任务执行失败] room={}, type={}, version={}, 将由兜底任务保障",
                    roomId, event.getEventType().getDesc(), event.getExpireVersion(), e);
        }
    }

    /**
     * 安全 sleep，不抛异常
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}