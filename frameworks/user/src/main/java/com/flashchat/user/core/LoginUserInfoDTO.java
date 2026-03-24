package com.flashchat.user.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录用户上下文信息载体
 * <p>
 * 生命周期：
 * <ul>
 *   <li>登录时构建，存入 SaToken Session</li>
 *   <li>每次 HTTP 请求，由 {@link UserContextInterceptor} 从 Session 取出，塞入 {@link UserContext} ThreadLocal</li>
 *   <li>请求结束，由 {@link UserContextInterceptor#afterCompletion} 清理 ThreadLocal</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>只存高频使用的轻量字段，不存业务详情（如 avatarColor）</li>
 *   <li>实现 Serializable，因为要存入 SaToken Redis Session</li>
 *   <li>字段变更时需同步更新 Session（如改昵称后调 session.set）</li>
 * </ul>
 * <p>
 * 存储位置：SaSession 中的 key 为 "userInfo"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUserInfoDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * SaSession 中存储本对象的 key
     */
    public static final String SESSION_KEY = "flashchat:login:userInfo";

    /**
     * 用户数据库主键 ID
     * 匿名成员 = t_member.id
     * 注册用户 = t_user.id
     */
    private Long loginId;

    /**
     * 用户类型
     * 取值见 {@link com.flashchat.user.constant.UserTypeConstant}
     * <ul>
     *   <li>0 = 匿名成员（MEMBER）</li>
     *   <li>1 = 注册用户（USER）</li>
     * </ul>
     */
    private Integer userType;

    /**
     * 账号 ID（仅匿名成员有值）
     * 格式：FC-XXXXXX（如 FC-8A3D7K）
     * 注册用户此字段为 null
     */
    private String accountId;

    /**
     * 昵称
     * 匿名成员 = t_member.nickname（如"神秘的猫咪"）
     * 注册用户 = t_user.username
     * 改昵称时需同步更新 Session 中的此字段
     */
    private String nickname;

    /**
     * 当前会话的 SaToken token 值
     * 登录时写入，便于后续需要 token 的场景使用
     * 例如：WS 握手时从 Session 反查 token 做匹配
     */
    private String token;
}