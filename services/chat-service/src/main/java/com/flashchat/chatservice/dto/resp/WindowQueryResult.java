package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 消息滑动窗口查询结果
 * 封装窗口查询的数据 + 元数据，供 ChatServiceImpl 判断 isLast。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WindowQueryResult {

    /** 查询到的消息列表（倒序，score 从高到低） */
    private List<ChatBroadcastMsgRespDTO> messages;

    /** 窗口中最小的 score（最旧消息的 dbId），用于 isLast 边界判断 */
    private Long windowMinScore;
}