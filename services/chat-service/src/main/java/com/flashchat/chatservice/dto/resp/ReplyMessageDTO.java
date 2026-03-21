package com.flashchat.chatservice.dto.resp;


import com.flashchat.chatservice.dto.msg.FileDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 引用回复消息 DTO
 * 对齐 vue-advanced-chat 的 replyMessage 对象格式
 * vue-advanced-chat 要求格式：
 *   {
 *     content: "被回复的原文",
 *     senderId: "user-2",
 *     files: [{...}]
 *   }
 * 嵌套在 ChatBroadcastMsgRespDTO.replyMessage 中
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplyMessageDTO {

    /**
     * 被回复消息的文本内容/摘要
     * 如果被回复消息已删除，显示"原消息已被删除"
     */
    private String content;

    /**
     * 被回复消息的发送者 ID
     */
    private String senderId;

    /**
     * 被回复消息的附件文件列表
     * 被回复消息是文本时为 null
     * 被回复消息已删除时为 null
     */
    private List<FileDTO> files;
}