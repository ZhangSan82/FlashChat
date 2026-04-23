package com.flashchat.userservice.controller;

import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dto.req.SubmitFeedbackReqDTO;
import com.flashchat.userservice.dto.resp.FeedbackSubmitRespDTO;
import com.flashchat.userservice.service.feedback.FeedbackService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户反馈提交接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/feedback")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public Result<FeedbackSubmitRespDTO> submitFeedback(@Valid @RequestBody SubmitFeedbackReqDTO request) {
        log.info("[feedback submit] accountId={}, type={}, sourcePage={}, sourceScene={}",
                UserContext.getAccountId(), request.getFeedbackType(), request.getSourcePage(), request.getSourceScene());
        return Results.success(feedbackService.submitFeedback(request));
    }
}
