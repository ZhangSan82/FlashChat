package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 消息表情回应 WS 广播数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MsgReactionRespDTO {

    /**
     * 消息业务 ID
     */
    private String msgId;

    /**
     * 消息自增 ID
     */
    private Long indexId;

    /**
     * 完整的 reactions Map
     */
    private Map<String, List<String>> reactions;
}
