package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 房间人数扩容请求
 */
@Data
public class RoomResizeReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * 新的最大人数
     */
    @NotNull(message = "新的最大人数不能为空")
    @Min(value = 2, message = "最大人数不能小于 2")
    @Max(value = 200, message = "最大人数不能超过 200")
    private Integer newMaxMembers;
}