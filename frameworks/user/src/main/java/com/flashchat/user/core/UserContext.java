package com.flashchat.user.core;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.flashchat.user.constant.UserTypeConstant;

import java.util.Optional;

/**
 * 用户上下文 — TransmittableThreadLocal 实现
 * <p>
 * 职责：在一次 HTTP 请求的调用链中透传当前登录用户信息，
 * 避免 Controller → Service → DAO 层层传参。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>请求进入：{@link UserContextInterceptor#preHandle} 设置</li>
 *   <li>请求处理中：业务代码通过静态方法获取</li>
 *   <li>请求结束：{@link UserContextInterceptor#afterCompletion} 清理</li>
 * </ol>
 * <p>
 * 不提供 getToken() 方法：token 是请求级别信息，不属于用户上下文。
 * HTTP 场景用 {@code StpUtil.getTokenValue()}，WS 场景用 {@code ChannelAttrUtil.getToken()}。
 * <p>
 * 适用范围：仅 HTTP 请求线程。WebSocket 长连接使用 ChannelAttrUtil。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUserInfoDTO> USER_THREAD_LOCAL =
            new TransmittableThreadLocal<>();

    private UserContext() {
    }

    // ==================== 写操作（仅 Interceptor 调用）====================

    public static void setUser(LoginUserInfoDTO user) {
        USER_THREAD_LOCAL.set(user);
    }

    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }

    // ==================== 读操作 ====================

    public static LoginUserInfoDTO getUser() {
        return USER_THREAD_LOCAL.get();
    }

    /**
     * 获取 t_account.id
     */
    public static Long getLoginId() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getLoginId)
                .orElse(null);
    }

    /**
     * 获取 t_account.id（不允许为空）
     * @throws IllegalStateException 未登录
     */
    public static Long getRequiredLoginId() {
        Long loginId = getLoginId();
        if (loginId == null) {
            throw new IllegalStateException("用户未登录，无法获取 loginId");
        }
        return loginId;
    }

    /**
     * 获取用户类型
     * @see UserTypeConstant
     */
    public static Integer getUserType() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getUserType)
                .orElse(null);
    }

    /**
     * 获取账号 ID（FC-XXXXXX）
     */
    public static String getAccountId() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getAccountId)
                .orElse(null);
    }

    /**
     * 获取昵称
     */
    public static String getNickname() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getNickname)
                .orElse(null);
    }

    // ==================== 身份判断 ====================

    public static boolean isLogin() {
        return USER_THREAD_LOCAL.get() != null;
    }

    public static boolean isMember() {
        Integer userType = getUserType();
        return userType != null && userType == UserTypeConstant.MEMBER;
    }

    public static boolean isRegisteredUser() {
        Integer userType = getUserType();
        return userType != null && userType == UserTypeConstant.USER;
    }
}