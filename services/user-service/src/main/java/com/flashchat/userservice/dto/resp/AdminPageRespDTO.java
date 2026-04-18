package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 管理端简单分页响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPageRespDTO<T> {

    private Long page;
    private Long size;
    private Long total;
    private List<T> records;
}
