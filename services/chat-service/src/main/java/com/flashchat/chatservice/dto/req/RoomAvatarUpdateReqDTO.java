package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Update room avatar request.
 */
@Data
public class RoomAvatarUpdateReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * avatar URL, null/blank means clear
     */
    @Size(max = 512, message = "房间头像 URL 过长")
    private String avatarUrl;
}
