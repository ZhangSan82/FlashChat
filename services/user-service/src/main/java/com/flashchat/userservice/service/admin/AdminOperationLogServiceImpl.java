package com.flashchat.userservice.service.admin;

import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.mapper.AdminOperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理员操作日志实现。
 */
@Service
@RequiredArgsConstructor
public class AdminOperationLogServiceImpl implements AdminOperationLogService {

    private final AdminOperationLogMapper adminOperationLogMapper;

    @Override
    public void record(AdminOperationLogDO logDO) {
        if (logDO == null) {
            return;
        }
        adminOperationLogMapper.insert(logDO);
    }
}
