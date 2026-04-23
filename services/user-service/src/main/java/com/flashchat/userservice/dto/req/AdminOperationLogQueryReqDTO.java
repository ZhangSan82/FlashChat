package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 管理员操作日志查询参数。
 */
@Data
public class AdminOperationLogQueryReqDTO {

    /**
     * 操作人业务账号 ID，支持模糊搜索。
     */
    private String operatorAccountId;

    /**
     * 操作类型枚举名，例如 ACCOUNT_BAN。
     */
    private String operationType;

    /**
     * 操作对象类型枚举名，例如 ACCOUNT / ROOM。
     */
    private String targetType;

    /**
     * 目标对象标识，账号场景一般为 accountId，房间场景为 roomId。
     */
    private String targetId;

    @Min(1)
    private Long page = 1L;

    @Min(1)
    @Max(100)
    private Long size = 20L;
}
