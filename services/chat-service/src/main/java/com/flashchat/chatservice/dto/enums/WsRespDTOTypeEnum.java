package com.flashchat.chatservice.dto.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WsRespDTOTypeEnum {
    CHAT_BROADCAST(1, "聊天消息广播"),
    USER_JOIN(2, "用户加入"),
    USER_LEAVE(3, "用户离开"),
    YOU_MUTED(4, "你被禁言"),
    YOU_UNMUTED(5, "你被解除禁言"),
    YOU_KICKED(6, "你被踢出"),
    ROOM_EXPIRING(7, "房间即将到期"),
    ROOM_GRACE(8, "房间宽限期"),
    ROOM_CLOSED(9, "房间已关闭"),
    SYSTEM_MSG(10, "系统消息"),
    MSG_REJECTED(11, "消息被拒绝"),
    MSG_RECALLED(12, "消息被撤回"),
    IDENTITY_ASSIGNED(13,"身份分配通知"),    // ★ 新增：身份分配通知
    HEARTBEAT_REPLY(14,"心跳回复");      // ★ 新增：心跳回复

    private final Integer type;
    private final String desc;
}
