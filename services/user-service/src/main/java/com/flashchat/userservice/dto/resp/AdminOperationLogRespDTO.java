package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员操作日志响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOperationLogRespDTO {

    private Long id;
    private Long operatorId;
    private String operatorAccountId;
    private String operationType;
    private String operationTypeDesc;
    private String targetType;
    private String targetTypeDesc;
    private String targetId;
    private String targetDisplay;
    private String reason;
    private String detailJson;
    private LocalDateTime createTime;
}
