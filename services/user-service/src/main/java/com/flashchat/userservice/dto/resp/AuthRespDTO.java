package com.flashchat.userservice.dto.resp;

import com.flashchat.userservice.dao.entity.AccountDO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证响应。
 * <p>
 * 登录、自动注册、登录态检查统一复用这一份结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthRespDTO {

    /** 当前会话 token。 */
    private String token;

    /** 面向用户展示的业务账号 ID。 */
    private String accountId;

    /** 昵称。 */
    private String nickname;

    /** 头像背景色。 */
    private String avatarColor;

    /** 头像 URL。 */
    private String avatarUrl;

    /** 是否已完成注册。 */
    private Boolean isRegistered;

    /** 系统角色。0=普通用户，1=管理员。 */
    private Integer systemRole;

    /** 当前账号是否是管理员。 */
    private Boolean isAdmin;

    public static AuthRespDTO from(AccountDO account, String token) {
        return AuthRespDTO.builder()
                .token(token)
                .accountId(account.getAccountId())
                .nickname(account.getNickname())
                .avatarColor(account.getAvatarColor())
                .avatarUrl(account.getAvatarUrl())
                .isRegistered(account.registered())
                .systemRole(account.getSystemRole())
                .isAdmin(account.isAdmin())
                .build();
    }
}
