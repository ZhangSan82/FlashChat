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
 * <ul>
 *   <li>请求进入：{@link UserContextInterceptor#preHandle} 从 SaSession 取出用户信息，调 {@link #setUser}</li>
 *   <li>请求处理中：业务代码通过 {@link #getLoginId()} 等静态方法获取当前用户</li>
 *   <li>请求结束：{@link UserContextInterceptor#afterCompletion} 调 {@link #removeUser} 清理</li>
 * </ul>
 * <p>
 * 为什么用 TransmittableThreadLocal 而不是普通 ThreadLocal：
 * <ul>
 *   <li>普通 ThreadLocal 在提交任务到线程池时，子线程拿不到父线程的值</li>
 *   <li>TTL 能自动将父线程的值传递给线程池中的子线程（如 @Async 场景）</li>
 *   <li>前提：线程池需要用 {@code TtlExecutors.getTtlExecutorService()} 包装</li>
 * </ul>
 * <p>
 * 适用范围：仅 HTTP 请求线程。WebSocket 长连接场景使用 ChannelAttrUtil，不碰此类。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUserInfoDTO> USER_THREAD_LOCAL =
            new TransmittableThreadLocal<>();

    private UserContext() {
    }

    // ==================== 写操作 ====================

    /**
     * 设置当前登录用户信息
     * <p>
     * 仅由 {@link UserContextInterceptor} 调用，业务代码不应直接调用
     */
    public static void setUser(LoginUserInfoDTO user) {
        USER_THREAD_LOCAL.set(user);
    }

    /**
     * 清理当前线程的用户上下文
     * <p>
     * 必须在请求结束时调用，否则线程池复用线程时会读到脏数据
     */
    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }

    // ==================== 读操作 ====================

    /**
     * 获取完整的用户信息对象
     *
     * @return 用户信息，未登录时返回 null
     */
    public static LoginUserInfoDTO getUser() {
        return USER_THREAD_LOCAL.get();
    }

    /**
     * 获取当前登录用户的数据库主键 ID
     * <p>
     * 匿名成员 = t_member.id，注册用户 = t_user.id
     *
     * @return 用户 ID，未登录时返回 null
     */
    public static Long getLoginId() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getLoginId)
                .orElse(null);
    }

    /**
     * 获取当前登录用户的数据库主键 ID（不允许为空）
     * <p>
     * 用于必须登录才能访问的接口，调用方不需要再做 null 判断
     *
     * @return 用户 ID
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
     *
     * @return 用户类型常量，见 {@link UserTypeConstant}，未登录时返回 null
     */
    public static Integer getUserType() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getUserType)
                .orElse(null);
    }

    /**
     * 获取账号 ID（仅匿名成员有值，如 FC-8A3D7K）
     *
     * @return 账号 ID，注册用户或未登录时返回 null
     */
    public static String getAccountId() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getAccountId)
                .orElse(null);
    }

    /**
     * 获取昵称
     *
     * @return 昵称，未登录时返回 null
     */
    public static String getNickname() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getNickname)
                .orElse(null);
    }

    /**
     * 获取当前会话的 token
     *
     * @return token 值，未登录时返回 null
     */
    public static String getToken() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get())
                .map(LoginUserInfoDTO::getToken)
                .orElse(null);
    }

    // ==================== 身份判断 ====================

    /**
     * 当前是否已登录
     */
    public static boolean isLogin() {
        return USER_THREAD_LOCAL.get() != null;
    }

    /**
     * 当前是否为匿名成员
     */
    public static boolean isMember() {
        Integer userType = getUserType();
        return userType != null && userType == UserTypeConstant.MEMBER;
    }

    /**
     * 当前是否为注册用户
     */
    public static boolean isRegisteredUser() {
        Integer userType = getUserType();
        return userType != null && userType == UserTypeConstant.USER;
    }
}