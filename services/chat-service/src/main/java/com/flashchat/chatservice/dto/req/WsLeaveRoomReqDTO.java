package com.flashchat.chatservice.dto.req;

import lombok.Data;

/**
 * 客户端通过 WS 发送"离开房间"指令时，data 部分的结构
 * 完整消息示例：
 * {
 *   "type": 2,
 *   "data": {
 *     "roomId": "room_abc"
 *   }
 * }
 * 注意：这是客户端→服务端的【请求体】
 * 不要和 UserLeaveMsgReqDTO 搞混，那个是服务端→客户端的【广播通知体】
 */
@Data
public class WsLeaveRoomReqDTO {

    /** 要离开的房间ID */
    private String roomId;
}
