package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息撤回/删除
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MsgRecallRespDTO {

    /**
     * 消息业务 ID
     */
    private String msgId;

    /**
     * 消息自增 ID
     */
    private Long indexId;

    /**
     * 消息发送者 ID
     */
    private String senderId;
}