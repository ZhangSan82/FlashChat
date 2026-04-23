package com.flashchat.userservice.service.admin;

import com.flashchat.userservice.dto.req.AdminFeedbackProcessReqDTO;
import com.flashchat.userservice.dto.req.AdminFeedbackQueryReqDTO;
import com.flashchat.userservice.dto.resp.AdminFeedbackRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;

/**
 * 管理员反馈处理服务。
 */
public interface AdminFeedbackService {

    AdminPageRespDTO<AdminFeedbackRespDTO> searchFeedbacks(Long operatorId, AdminFeedbackQueryReqDTO request);

    AdminFeedbackRespDTO getFeedbackDetail(Long operatorId, Long feedbackId);

    void processFeedback(Long operatorId, Long feedbackId, AdminFeedbackProcessReqDTO request);
}
