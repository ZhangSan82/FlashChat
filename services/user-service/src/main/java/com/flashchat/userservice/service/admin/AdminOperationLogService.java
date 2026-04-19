package com.flashchat.userservice.service.admin;

import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dto.req.AdminOperationLogQueryReqDTO;
import com.flashchat.userservice.dto.resp.AdminOperationLogRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;

/**
 * 管理员操作日志服务。
 */
public interface AdminOperationLogService {

    /**
     * 记录一条管理员操作日志。
     */
    void record(AdminOperationLogDO logDO);

    /**
     * 分页查询管理员操作日志。
     */
    AdminPageRespDTO<AdminOperationLogRespDTO> searchLogs(Long operatorId, AdminOperationLogQueryReqDTO request);
}
