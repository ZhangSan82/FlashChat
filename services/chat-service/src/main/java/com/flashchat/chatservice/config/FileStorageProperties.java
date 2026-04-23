package com.flashchat.chatservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储配置。
 * 当前统一使用阿里云 OSS，不再提供本地落盘模式。
 */
@Data
@ConfigurationProperties(prefix = "flashchat.file")
public class FileStorageProperties {

    /**
     * OSS 相关配置，仅 oss 模式使用。
     */
    private OssProperties oss = new OssProperties();

    @Data
    public static class OssProperties {

        /**
         * OSS Endpoint，例如 https://oss-cn-hangzhou.aliyuncs.com
         */
        private String endpoint;

        /**
         * Bucket 名称。
         */
        private String bucket;

        /**
         * 对外访问域名，未配置时根据 endpoint + bucket 自动推导。
         * 若接入 CDN / 自定义域名，建议显式配置。
         */
        private String publicBaseUrl;

        /**
         * OSS 对象前缀，方便与其他业务文件隔离。
         */
        private String objectPrefix = "flashchat";

        /**
         * 阿里云 AccessKey ID，建议通过环境变量注入。
         */
        private String accessKeyId;

        /**
         * 阿里云 AccessKey Secret，建议通过环境变量注入。
         */
        private String accessKeySecret;
    }
}
