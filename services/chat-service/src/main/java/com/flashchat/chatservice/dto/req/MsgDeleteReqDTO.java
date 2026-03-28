package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 消息删除请求
 */
@Data
public class MsgDeleteReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * 要删除的消息 ID（t_message.id）
     */
    @NotNull(message = "消息 ID 不能为空")
    private Long msgId;
}
