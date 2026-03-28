package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 房间时长延期请求
 */
@Data
public class RoomExtendReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * 延期时长档位
     */
    private String duration;
}
