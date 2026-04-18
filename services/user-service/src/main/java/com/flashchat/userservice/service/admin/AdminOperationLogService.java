package com.flashchat.userservice.service.admin;

import com.flashchat.userservice.dao.entity.AdminOperationLogDO;

/**
 * 管理员操作日志服务。
 */
public interface AdminOperationLogService {

    /**
     * 持久化一条管理员操作日志。
     */
    void record(AdminOperationLogDO logDO);
}
