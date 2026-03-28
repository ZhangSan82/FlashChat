package com.flashchat.userservice.dto.resp;


import com.flashchat.userservice.dao.entity.AccountDO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证响应（注册/登录/检查登录状态 统一返回）
 * <p>
 * 所有认证相关接口返回同一结构，前端只需处理一种格式。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthRespDTO {

    /** SaToken 令牌，前端存入 localStorage，后续请求放 Header: satoken=xxx */
    private String token;

    /** 面向用户的账号 ID（FC-XXXXXX） */
    private String accountId;

    /** 昵称 */
    private String nickname;

    /** 头像背景色（如 #FF6B6B） */
    private String avatarColor;

    /** 头像 URL（注册后可上传，匿名阶段为空） */
    private String avatarUrl;

    /** 是否已注册（true=注册用户，false=匿名成员） */
    private Boolean isRegistered;

    /**
     * 从 AccountDO 构建认证响应（工厂方法）
     * <p>
     * 用于 /check 等已知 token 的场景，避免每个接口重复写 builder
     *
     * @param account 账号实体
     * @param token   当前会话的 SaToken 值
     */
    public static AuthRespDTO from(AccountDO account, String token) {
        return AuthRespDTO.builder()
                .token(token)
                .accountId(account.getAccountId())
                .nickname(account.getNickname())
                .avatarColor(account.getAvatarColor())
                .avatarUrl(account.getAvatarUrl())
                .isRegistered(account.registered())
                .build();
    }
}