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

    HEARTBEAT(1, "心跳"),
    SEND_MSG(2, "发送消息"),

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