package com.flashchat.user.core;

import cn.dev33.satoken.session.SaSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 登录用户上下文信息载体
 * 生命周期：
 * <ol>
 *   <li>登录时构建，存入 SaToken Session（key = {@link #SESSION_KEY}）</li>
 *   <li>每次 HTTP 请求，由 {@link UserContextInterceptor} 从 Session 取出，塞入 {@link UserContext}</li>
 *   <li>请求结束，由 {@link UserContextInterceptor#afterCompletion} 清理 ThreadLocal</li>
 * </ol>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>只存高频使用的轻量字段，不存业务详情（如 avatarColor）</li>
 *   <li>实现 Serializable，因为要存入 SaToken Redis Session</li>
 *   <li>字段变更时需同步更新 Session（如改昵称后调 session.set）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUserInfoDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * SaSession 内部 dataMap 的 field 名称
     * <p>
     * 注意：这不是 Redis key，是 SaSession 对象内的属性名
     * <p>
     *   SaToken 的 SaSession 内部是一个 Map<String, Object>，这是 map 的 key。
     *   使用方式：session.set("userInfo", dto) 和 session.getModel("userInfo", LoginUserInfoDTO.class)
     *   Redis 中的实际 key 结构是：satoken:login:session:loginId值 → 内部 dataMap → "userInfo" → DTO
     */

    public static final String SESSION_KEY = "userInfo";

    /**
     * 用户数据库主键 ID（t_account.id）
     * <p>
     * 合表后统一身份，匿名成员和注册用户共用同一张表
     */
    private Long loginId;

    /**
     * 用户类型
     * <p>
     * 取值见 {@link com.flashchat.user.constant.UserTypeConstant}
     * <ul>
     *   <li>0 = 匿名成员（t_account.is_registered = 0）</li>
     *   <li>1 = 注册用户（t_account.is_registered = 1）</li>
     * </ul>
     */
    private Integer userType;

    /**
     * 面向用户的账号 ID（t_account.account_id）
     * <p>
     * 格式：FC-XXXXXX（如 FC-8A3D7K）
     */
    private String accountId;

    /**
     * 昵称（t_account.nickname）
     * <p>
     * 改昵称时需同步更新 Session：
     * {@code StpUtil.getSession().set(SESSION_KEY, updatedUserInfo)}
     */
    private String nickname;
}