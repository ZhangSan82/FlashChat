package com.flashchat.userservice.service.feedback;

import com.flashchat.userservice.dto.req.SubmitFeedbackReqDTO;
import com.flashchat.userservice.dto.resp.FeedbackSubmitRespDTO;

/**
 * 用户反馈服务。
 */
public interface FeedbackService {

    FeedbackSubmitRespDTO submitFeedback(SubmitFeedbackReqDTO request);
}
