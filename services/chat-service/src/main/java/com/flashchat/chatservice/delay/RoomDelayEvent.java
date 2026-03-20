package com.flashchat.chatservice.delay;

import com.flashchat.chatservice.dto.enums.RoomDelayEventTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 房间延时队列消息体
 * 存入 Redisson RDelayedQueue，到期后被消费者取出执行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomDelayEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 房间业务 ID */
    private String roomId;

    /** 事件类型 */
    private RoomDelayEventTypeEnum eventType;

    /**
     * 到期版本号
     * 每次延期 +1，消费时与 DB 中的 expire_version 比较
     * 不匹配则说明房间已延期，该任务作废
     */
    private Integer expireVersion;

    /** 预期触发时间戳（毫秒）*/
    private Long expectedTriggerTime;
}