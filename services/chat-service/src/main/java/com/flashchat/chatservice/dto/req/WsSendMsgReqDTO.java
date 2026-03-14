package com.flashchat.chatservice.dto.req;

import lombok.Data;

/**
 * 客户端通过 WS 发送聊天消息时，data 部分的结构
 * 完整消息示例：
 * {
 *   "type": 3,
 *   "data": {
 *     "roomId": "room_abc",
 *     "content": "大家好！"
 *   }
 * }
 * 注意区别于 SendMsgReqDTO（HTTP接口用的，需要传 memberId）
 * WS 连接已绑定用户身份，所以不需要传 memberId
 */
@Data
public class WsSendMsgReqDTO {

    /** 目标房间ID（指定消息发到哪个房间） */
    private String roomId;

    /** 消息内容 */
    private String content;
}