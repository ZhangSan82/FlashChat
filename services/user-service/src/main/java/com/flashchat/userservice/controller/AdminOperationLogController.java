package com.flashchat.userservice.controller;

import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dto.req.AdminOperationLogQueryReqDTO;
import com.flashchat.userservice.dto.resp.AdminOperationLogRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import com.flashchat.userservice.service.admin.AdminOperationLogService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员操作日志接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/admin/logs")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AdminOperationLogController {

    private final AdminOperationLogService adminOperationLogService;

    @GetMapping
    public Result<AdminPageRespDTO<AdminOperationLogRespDTO>> searchLogs(@Valid AdminOperationLogQueryReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin operation logs] operator={}, operationType={}, targetType={}, targetId={}",
                operatorId, request.getOperationType(), request.getTargetType(), request.getTargetId());
        return Results.success(adminOperationLogService.searchLogs(operatorId, request));
    }
}
