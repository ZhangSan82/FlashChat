package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 消息撤回请求
 */
@Data
public class MsgRecallReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * 要撤回的消息 ID（t_message.id，即前端的 indexId / dbId）
     */
    @NotNull(message = "消息 ID 不能为空")
    private Long msgId;
}