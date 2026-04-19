package com.flashchat.user.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.flashchat.user.core.UserContextInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截规则与 UserContext 透传配置。
 */
public class SaTokenConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            SaRouter.match("/api/**")
                    .notMatch(
                            "/api/FlashChat/v1/account/auto-register",
                            "/api/FlashChat/v1/account/login",
                            "/api/FlashChat/v1/feedback",
                            "/api/FlashChat/v1/room/public",
                            "/api/FlashChat/v1/room/public/**",
                            "/api/FlashChat/v1/room/pricing",
                            "/api/FlashChat/v1/room/preview/**",
                            "/error"
                    )
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/api/**").order(0);

        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/api/**")
                .order(1);
    }
}
