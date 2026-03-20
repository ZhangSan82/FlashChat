package com.flashchat.chatservice.dto.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 房间延时任务类型
 * 一个房间的完整生命周期会依次触发：
 * EXPIRING_SOON → EXPIRED → GRACE_END
 */
@Getter
@AllArgsConstructor
public enum RoomDelayEventTypeEnum {

    /**
     * 即将到期提醒
     * 触发时间：expireTime - 5 分钟
     * 动作：status → EXPIRING(2)，WS 通知房间即将到期
     */
    EXPIRING_SOON(1, "即将到期提醒"),

    /**
     * 已到期，进入宽限期
     * 触发时间：expireTime
     * 动作：status → GRACE(3)，WS 通知进入宽限期
     */
    EXPIRED(2, "已到期-进入宽限期"),

    /**
     * 宽限期结束，正式关闭
     * 触发时间：expireTime + 5 分钟
     * 动作：status → CLOSED(4)，执行完整关闭流程
     */
    GRACE_END(3, "宽限期结束-正式关闭");

    private final int code;
    private final String desc;
}