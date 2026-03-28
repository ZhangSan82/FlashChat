package com.flashchat.chatservice.dto.req;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 消息表情回应请求
 */
@Data
public class MsgReactionReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * 目标消息 ID
     */
    @NotNull(message = "消息 ID 不能为空")
    private Long msgId;

    /**
     * emoji 字符
     */
    @NotBlank(message = "emoji 不能为空")
    @Size(max = 32, message = "emoji 格式不合法")
    private String emoji;
}
