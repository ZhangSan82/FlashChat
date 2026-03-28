package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 公开房间列表请求
 */
@Data
public class PublicRoomListReqDTO {

    /**
     * 页码，从 1 开始
     */
    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    /**
     * 每页条数
     */
    @Min(value = 1, message = "每页最少 1 条")
    @Max(value = 50, message = "每页最多 50 条")
    private Integer size = 20;

    /**
     * 排序方式
     */
    private String sort = "hot";
}