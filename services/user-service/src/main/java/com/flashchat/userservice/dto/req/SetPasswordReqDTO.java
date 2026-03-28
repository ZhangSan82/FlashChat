package com.flashchat.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 首次设置密码请求
 */
@Data
public class SetPasswordReqDTO {

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度 6-32 字符")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
}