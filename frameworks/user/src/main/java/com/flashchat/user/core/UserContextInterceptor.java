package com.flashchat.user.core;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文拦截器（order=1，排在 SaToken 拦截器之后）
 * <p>
 * 职责：从 SaSession 读 {@link LoginUserInfoDTO} → 塞入 {@link UserContext} ThreadLocal
 * <p>
 * 对于放行的公开接口，如果请求碰巧带了有效 token，也会设置 UserContext。
 * <p>
 * 为什么用 getSessionByLoginId(loginId, false) 而不是 StpUtil.getSession()：
 * 放行接口不经过 SaInterceptor 校验，StpUtil.getSession() 的行为可能不一致。
 * 显式传 loginId + false（不自动创建）是最安全的写法。
 * 虽然多一次 Redis 调用，但语义明确、行为确定。
 */
@Slf4j
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {

        /**
         * 通过前端的token获取当前登录 ID，未登录返回 null
         */
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            SaSession session = StpUtil.getSessionByLoginId(loginId, false);
            if (session != null) {
                LoginUserInfoDTO userInfo = session.getModel(
                        LoginUserInfoDTO.SESSION_KEY, LoginUserInfoDTO.class);
                if (userInfo != null) {
                    UserContext.setUser(userInfo);
                }
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.removeUser();
    }
}