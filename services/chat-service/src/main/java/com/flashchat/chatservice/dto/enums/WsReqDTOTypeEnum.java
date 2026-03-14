package com.flashchat.chatservice.dto.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 客户端 → 服务端 的 WebSocket 请求类型
 * 区别于 WsRespDTOTypeEnum（服务端→客户端的推送类型）
 * 这是客户端发给服务端的指令类型
 * 客户端消息格式：{"type": 1, "data": {...}}
 */
@Getter
@AllArgsConstructor
public enum WsReqDTOTypeEnum {
    // ===== 基础操作 =====
    JOIN_ROOM(1, "加入房间"),
    LEAVE_ROOM(2, "离开房间"),
    SEND_MSG(3, "发送消息"),
    HEARTBEAT(4, "心跳"),

    // ===== 管理操作（需要房主权限）=====
    MUTE(5, "禁言"),
    UNMUTE(6, "解除禁言"),
    KICK(7, "踢人"),
    CLOSE_ROOM(8, "关闭房间"),
    ;

    private final Integer type;
    private final String desc;

    public static WsReqDTOTypeEnum of(Integer type) {
        if (type == null) return null;
        for (WsReqDTOTypeEnum value : values()) {
            if (value.type.equals(type)) {
                return value;
            }
        }
        return null;
    }
}