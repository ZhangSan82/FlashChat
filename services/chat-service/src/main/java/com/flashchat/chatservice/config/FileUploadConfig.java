package com.flashchat.chatservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 文件访问配置。
 * 当前全部文件资源都走阿里云 OSS 公网地址，因此这里不再注册本地 /uploads/** 映射。
 */
@Configuration
public class FileUploadConfig implements WebMvcConfigurer {
}
