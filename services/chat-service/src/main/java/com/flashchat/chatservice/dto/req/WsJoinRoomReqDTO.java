package com.flashchat.chatservice.dto.req;

import lombok.Data;

/**
 * 客户端通过 WS 发送"加入房间"指令时，data 部分的结构
 *
 * 完整消息示例：
 * {
 *   "type": 1,
 *   "data": {
 *     "roomId": "room_abc",
 *     "nickname": "Alice",
 *     "avatar": "#FF5733",
 *     "isHost": false
 *   }
 * }
 *
 * 注意：这是客户端→服务端的【请求体】
 * 不要和 UserJoinMsgReqDTO 搞混，那个是服务端→客户端的【广播通知体】
 */
@Data
public class WsJoinRoomReqDTO {

    /** 要加入的房间ID */
    private String roomId;

    /** 在该房间使用的昵称 */
    private String nickname;

    /** 在该房间使用的头像 */
    private String avatar;

    /** 是否为房主 */
    private Boolean isHost;
}