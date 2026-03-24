package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 加入房间请求
 * memberId = 匿名成员 t_member.id
 */
@Data
public class RoomJoinReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

}