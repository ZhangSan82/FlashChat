package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 匿名成员登录请求
 * <p>
 * 只有设了密码的匿名用户才能使用此接口重新登录。
 * 未设密码的匿名用户 token 丢失 = 身份丢失，需要重新注册。
 * <p>
 * password 字段设为可选：
 * 业务层根据账号是否设了密码做分支处理，
 * 而不是在 DTO 校验层强制要求（报错信息更友好）。
 */
@Data
public class MemberLoginReqDTO {

    @NotBlank(message = "账号 ID 不能为空")
    private String accountId;

    /** 密码（未设密码的账号不传此字段，后端会返回明确错误提示） */
    private String password;
}