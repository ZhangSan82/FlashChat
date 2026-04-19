package com.flashchat.userservice.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.enums.AdminOperationTargetTypeEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTypeEnum;
import com.flashchat.userservice.dao.mapper.AdminOperationLogMapper;
import com.flashchat.userservice.dto.req.AdminOperationLogQueryReqDTO;
import com.flashchat.userservice.dto.resp.AdminOperationLogRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理员操作日志服务实现。
 */
@Service
@RequiredArgsConstructor
public class AdminOperationLogServiceImpl implements AdminOperationLogService {

    private final AdminOperationLogMapper adminOperationLogMapper;
    private final AdminAuthService adminAuthService;

    @Override
    public void record(AdminOperationLogDO logDO) {
        if (logDO == null) {
            return;
        }
        adminOperationLogMapper.insert(logDO);
    }

    @Override
    public AdminPageRespDTO<AdminOperationLogRespDTO> searchLogs(Long operatorId, AdminOperationLogQueryReqDTO request) {
        adminAuthService.requireActiveAdmin(operatorId);

        long pageNo = request.getPage() == null || request.getPage() < 1 ? 1L : request.getPage();
        long size = request.getSize() == null || request.getSize() < 1 ? 20L : Math.min(request.getSize(), 100L);

        LambdaQueryWrapper<AdminOperationLogDO> wrapper = new LambdaQueryWrapper<>();
        if (hasText(request.getOperatorAccountId())) {
            wrapper.like(AdminOperationLogDO::getOperatorAccountId, request.getOperatorAccountId().trim());
        }
        if (hasText(request.getOperationType())) {
            wrapper.eq(AdminOperationLogDO::getOperationType, request.getOperationType().trim());
        }
        if (hasText(request.getTargetType())) {
            wrapper.eq(AdminOperationLogDO::getTargetType, request.getTargetType().trim());
        }
        if (hasText(request.getTargetId())) {
            wrapper.like(AdminOperationLogDO::getTargetId, request.getTargetId().trim());
        }
        wrapper.orderByDesc(AdminOperationLogDO::getId);

        Page<AdminOperationLogDO> result = adminOperationLogMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<AdminOperationLogRespDTO> records = result.getRecords().stream()
                .map(this::toResp)
                .toList();

        return AdminPageRespDTO.<AdminOperationLogRespDTO>builder()
                .page(pageNo)
                .size(size)
                .total(result.getTotal())
                .records(records)
                .build();
    }

    private AdminOperationLogRespDTO toResp(AdminOperationLogDO logDO) {
        AdminOperationTypeEnum operationType = AdminOperationTypeEnum.fromName(logDO.getOperationType());
        AdminOperationTargetTypeEnum targetType = AdminOperationTargetTypeEnum.fromName(logDO.getTargetType());
        return AdminOperationLogRespDTO.builder()
                .id(logDO.getId())
                .operatorId(logDO.getOperatorId())
                .operatorAccountId(logDO.getOperatorAccountId())
                .operationType(logDO.getOperationType())
                .operationTypeDesc(operationType == null ? logDO.getOperationType() : operationType.getDesc())
                .targetType(logDO.getTargetType())
                .targetTypeDesc(targetType == null ? logDO.getTargetType() : targetType.getDesc())
                .targetId(logDO.getTargetId())
                .targetDisplay(logDO.getTargetDisplay())
                .reason(logDO.getReason())
                .detailJson(logDO.getDetailJson())
                .createTime(logDO.getCreateTime())
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
