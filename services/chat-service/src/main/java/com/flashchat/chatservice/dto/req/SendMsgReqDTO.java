package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送消息的请求体
 */
@Data
public  class SendMsgReqDTO {

    @NotNull(message = "房间ID不能为空")
    private String roomId;

    @NotNull(message = "成员ID不能为空")
    private Long memberId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 500, message = "消息内容不能超过500字")
    private String content;
}