package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员处理反馈请求。
 */
@Data
public class AdminFeedbackProcessReqDTO {

    @NotBlank(message = "反馈状态不能为空")
    private String status;

    @Size(max = 1000, message = "处理备注不能超过 1000 个字符")
    private String reply;
}
