package com.flashchat.userservice.controller;

import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dto.req.AdminFeedbackProcessReqDTO;
import com.flashchat.userservice.dto.req.AdminFeedbackQueryReqDTO;
import com.flashchat.userservice.dto.resp.AdminFeedbackRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import com.flashchat.userservice.service.admin.AdminFeedbackService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员反馈处理接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/admin/feedbacks")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AdminFeedbackController {

    private final AdminFeedbackService adminFeedbackService;

    @GetMapping
    public Result<AdminPageRespDTO<AdminFeedbackRespDTO>> searchFeedbacks(@Valid AdminFeedbackQueryReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin search feedbacks] operator={}, status={}, type={}",
                operatorId, request.getStatus(), request.getFeedbackType());
        return Results.success(adminFeedbackService.searchFeedbacks(operatorId, request));
    }

    @GetMapping("/{feedbackId}")
    public Result<AdminFeedbackRespDTO> getFeedbackDetail(@PathVariable("feedbackId") Long feedbackId) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin feedback detail] operator={}, feedbackId={}", operatorId, feedbackId);
        return Results.success(adminFeedbackService.getFeedbackDetail(operatorId, feedbackId));
    }

    @PostMapping("/{feedbackId}/process")
    public Result<Void> processFeedback(@PathVariable("feedbackId") Long feedbackId,
                                        @Valid @RequestBody AdminFeedbackProcessReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin process feedback] operator={}, feedbackId={}, status={}",
                operatorId, feedbackId, request.getStatus());
        adminFeedbackService.processFeedback(operatorId, feedbackId, request);
        return Results.success();
    }
}
