package com.flashchat.chatservice.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SaToken 异常处理器
 * <p>
 * 为什么不放在 frameworks/convention 的 GlobalExceptionHandler 中：
 * convention 模块没有 SaToken 依赖，无法引用 NotLoginException。
 * SaToken 相关的异常处理跟着 SaToken 的使用方（chat-service）走，职责更清晰。
 * <p>
 * {@code @Order(-1)}：优先级高于 convention 的 GlobalExceptionHandler，
 * 确保 NotLoginException 被此 handler 优先捕获，不会落入通用 Exception handler。
 */
@Slf4j
@Order(-1)
@RestControllerAdvice
public class SaTokenExceptionHandler {

    /**
     * 未登录异常处理
     * <p>
     * SaToken 在以下场景抛出 NotLoginException：
     * <ul>
     *   <li>NOT_TOKEN：请求未携带 token</li>
     *   <li>INVALID_TOKEN：token 格式正确但 Redis 中不存在（伪造或已登出）</li>
     *   <li>TOKEN_TIMEOUT：token 已过期（超过 30 天）</li>
     *   <li>BE_REPLACED：被其他设备顶下线（is-concurrent=true 时不会触发）</li>
     *   <li>KICK_OUT：被管理员踢下线</li>
     * </ul>
     *
     * @return HTTP 401 + 错误码 A000401 + 具体错误消息
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleNotLoginException(HttpServletRequest request,
                                                NotLoginException e) {
        String message = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未提供认证令牌，请先登录";
            case NotLoginException.INVALID_TOKEN -> "认证令牌无效，请重新登录";
            case NotLoginException.TOKEN_TIMEOUT -> "认证令牌已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "账号已在其他设备登录";
            case NotLoginException.KICK_OUT -> "账号已被踢下线";
            default -> "未登录，请先登录";
        };

        log.warn("[SaToken 未登录] uri={}, type={}, message={}",
                request.getRequestURI(), e.getType(), message);

        return Results.failure("A000401", message);
    }
}