package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 游标分页请求（通用）

 */
@Data
public class CursorPageBaseReq {

    /**
     * 游标（上一页返回的 cursor 值）
     * 第一次请求传 null 或不传，表示从最新消息开始
     */
    private String cursor;

    /**
     * 每页条数，默认 20
     */
    @Min(value = 1, message = "每页最少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 20;
}