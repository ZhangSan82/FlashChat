package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 管理员反馈列表查询参数。
 */
@Data
public class AdminFeedbackQueryReqDTO {

    private String keyword;

    private String status;

    private String feedbackType;

    private String accountType;

    private String sourcePage;

    private String sourceScene;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    @Min(1)
    private Long page = 1L;

    @Min(1)
    @Max(100)
    private Long size = 20L;
}
