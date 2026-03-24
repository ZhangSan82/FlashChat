package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoomLeaveReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;


}