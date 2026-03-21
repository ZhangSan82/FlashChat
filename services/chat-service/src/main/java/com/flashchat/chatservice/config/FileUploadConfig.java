package com.flashchat.chatservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 文件上传静态资源配置
 * 功能：让 Spring 能直接伺服上传的文件
 *   存储路径：./uploads/2024/07/15/uuid.jpg
 *   访问URL：http://localhost:8081/uploads/2024/07/15/uuid.jpg
 * 原理：注册 ResourceHandler，把 /uploads/** 映射到本地磁盘目录
 * 生产环境：
 *   建议用 Nginx 直接伺服静态文件（性能更好）
 *   此时可以关闭此配置，或不影响（Nginx 先拦截 /uploads/ 路径，不到 Spring）
 * 未来迁移到 OSS 时：
 *   url-prefix 改为 OSS 域名，不再需要本地静态资源映射
 *   删除此配置类即可
 */
@Slf4j
@Configuration
public class FileUploadConfig implements WebMvcConfigurer {

    @Value("${flashchat.file.upload-path:./uploads}")
    private String uploadPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolutePath = Paths.get(uploadPath).toAbsolutePath().normalize().toUri().toString();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(absolutePath);

        log.info("[静态资源] /uploads/** → {}", absolutePath);
    }
}