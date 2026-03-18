package com.flashchat.chatservice.dto.resp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatBroadcastMsgRespDTO {
    private String _id;         // 消息唯一ID
    private Long dbId;          //消息自增ID
    private String content;     // 消息内容
    private String senderId;    // 发送者ID
    private String username;    // 发送者昵称
    private String avatar;      // 发送者头像
    private Long timestamp;     // 时间戳
    private Boolean isHost;     // 是否房主
}