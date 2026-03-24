package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 关闭房间请求
 */
@Data
public class RoomCloseReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;


}
