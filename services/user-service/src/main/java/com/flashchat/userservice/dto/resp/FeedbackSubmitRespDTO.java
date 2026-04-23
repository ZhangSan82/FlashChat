package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 反馈提交结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackSubmitRespDTO {

    private Long id;

    private String status;

    private String statusDesc;
}
