package com.flashchat.user.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * 用户上下文自动装配入口
 * <p>
 * 通过 Spring Boot SPI 文件注册（META-INF/spring/...imports），
 * 自动导入 {@link SaTokenConfiguration}。
 * <p>
 * 为什么 SaTokenConfiguration 不直接加 @Configuration：
 * 应用主类有 {@code scanBasePackages = "com.flashchat"}，
 * 如果 SaTokenConfiguration 带 @Configuration 会被扫描到，
 * 同时又被这里 @Import，虽然 Spring 能去重，但职责不清晰。
 * 框架类应统一通过自动装配加载，不依赖业务方的包扫描配置。
 */
@AutoConfiguration
@ConditionalOnWebApplication
@Import(SaTokenConfiguration.class)
public class UserContextAutoConfiguration {
}