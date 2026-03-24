package com.flashchat.user.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.flashchat.user.core.UserContextInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SaToken 路由拦截规则 + UserContext 拦截器注册
 * <p>
 * 不加 @Configuration 注解——仅通过 {@link UserContextAutoConfiguration}
 * 的 @Import 加载，避免与 scanBasePackages 重复注册。
 * <p>
 * 拦截器执行顺序：
 * <ol>
 *   <li>order=0：SaInterceptor — 校验 Token 有效性，无效则抛 NotLoginException</li>
 *   <li>order=1：UserContextInterceptor — 从 SaSession 读用户信息塞 ThreadLocal</li>
 * </ol>
 * <p>
 * 放行规则：
 * <ul>
 *   <li>匿名注册、登录接口必须放行（否则无法获取 Token）</li>
 *   <li>公开房间列表放行（未登录也可浏览）</li>
 *   <li>/uploads/** 不在 /api/** 下，天然不受拦截</li>
 *   <li>WS 端口 8090 是独立 Netty Server，不经过 Spring MVC，不受此配置影响</li>
 * </ul>
 */
public class SaTokenConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // ========== 1. SaToken 路由鉴权（order=0）==========
        registry.addInterceptor(new SaInterceptor(handle -> {
            SaRouter.match("/api/**")
                    .notMatch(
                            // 认证接口（无 Token 才需要调这些）
                            "/api/**/account/auto-register",
                            "/api/**/account/login",
                            // 公开数据
                            "/api/**/room/public",
                            // Spring 错误页
                            "/error"
                    )
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/api/**").order(0);

        // ========== 2. UserContext 上下文透传（order=1）==========
        // 对所有 /api/** 路径生效，包括放行路径
        // 放行路径如果碰巧带了有效 Token，也会设置 UserContext
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/api/**")
                .order(1);
    }
}
