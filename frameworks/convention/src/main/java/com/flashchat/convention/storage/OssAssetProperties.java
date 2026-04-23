package com.flashchat.convention.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 私有 OSS 资源访问配置。
 * 这里只负责“资源签名访问”的公共配置，不承担具体上传逻辑。
 */
@Data
@Component
@ConfigurationProperties(prefix = "flashchat.file.oss")
public class OssAssetProperties {

    /**
     * 例如 https://oss-cn-hangzhou.aliyuncs.com
     */
    private String endpoint;

    /**
     * Bucket 名称。
     */
    private String bucket;

    /**
     * 上传对象前缀，便于隔离业务目录。
     */
    private String objectPrefix = "flashchat";

    /**
     * 历史公网访问域名或自定义 CDN 域名。
     * 兼容老数据仍然存储完整 URL 的场景，读取时可反推出 objectKey 再做签名。
     */
    private String publicBaseUrl;

    /**
     * AccessKey ID。
     */
    private String accessKeyId;

    /**
     * AccessKey Secret。
     */
    private String accessKeySecret;

    /**
     * 预签名地址过期时间，默认 30 天。
     */
    private long signExpireSeconds = 30L * 24 * 60 * 60;

    public boolean isConfigured() {
        return StringUtils.hasText(endpoint)
                && StringUtils.hasText(bucket)
                && StringUtils.hasText(accessKeyId)
                && StringUtils.hasText(accessKeySecret);
    }
}
