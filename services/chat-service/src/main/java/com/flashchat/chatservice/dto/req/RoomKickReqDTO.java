package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoomKickReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * 操作者（房主）的 accountId
     */
    @NotBlank(message = "操作者账号 ID 不能为空")
    private String accountId;



    @NotNull(message = "被踢人 ID 不能为空")
    private Long targetAccountId;
}