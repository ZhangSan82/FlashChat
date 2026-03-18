package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 游标分页响应（通用）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CursorPageBaseResp<T> {

    /** 下一页游标，客户端下次请求时原样传回 */
    private String cursor;

    /** 是否最后一页（true 时客户端不再请求） */
    private Boolean isLast;

    /** 数据列表 */
    private List<T> list;
}