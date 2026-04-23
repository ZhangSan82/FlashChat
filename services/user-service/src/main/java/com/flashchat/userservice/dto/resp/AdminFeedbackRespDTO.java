package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员反馈详情/列表响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminFeedbackRespDTO {

    private Long id;
    private String accountId;
    private String nicknameSnapshot;
    private String accountType;
    private String accountTypeDesc;
    private String feedbackType;
    private String feedbackTypeDesc;
    private String content;
    private String contentPreview;
    private String contact;
    private String screenshotUrl;
    private Boolean willingContact;
    private String sourcePage;
    private String sourceScene;
    private String status;
    private String statusDesc;
    private String reply;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
