package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 需要填写原因的管理员操作请求。
 */
@Data
public class AdminOperationReasonReqDTO {

    @NotBlank(message = "操作原因不能为空")
    @Size(max = 200, message = "操作原因不能超过 200 个字符")
    private String reason;
}
