package com.flashchat.convention.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.Locale;

/**
 * OSS 私有资源稳定引用与访问签名服务。
 * 约定数据库中持久化的资源引用格式为 oss://{objectKey}，
 * 对外返回时再临时签名成可访问 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssAssetUrlService {

    public static final String OSS_REFERENCE_PREFIX = "oss://";

    private final OssAssetProperties properties;

    public boolean isStorageReference(String value) {
        return StringUtils.hasText(value) && value.trim().startsWith(OSS_REFERENCE_PREFIX);
    }

    public String buildStorageReference(String objectKey) {
        String normalized = trimSlashes(objectKey);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return OSS_REFERENCE_PREFIX + normalized;
    }

    public String extractObjectKey(String value) {
        if (!isStorageReference(value)) {
            return value;
        }
        return trimSlashes(value.trim().substring(OSS_REFERENCE_PREFIX.length()));
    }

    public String resolveAccessUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (!properties.isConfigured()) {
            return value;
        }

        String objectKey = extractResolvableObjectKey(value);
        if (!StringUtils.hasText(objectKey)) {
            return value;
        }

        OSS client = null;
        try {
            client = new OSSClientBuilder().build(
                    properties.getEndpoint(),
                    properties.getAccessKeyId(),
                    properties.getAccessKeySecret()
            );
            long expireSeconds = Math.max(properties.getSignExpireSeconds(), 60L);
            Date expiration = new Date(System.currentTimeMillis() + expireSeconds * 1000);
            URL signedUrl = client.generatePresignedUrl(properties.getBucket(), objectKey, expiration);
            return signedUrl.toString();
        } catch (Exception ex) {
            log.warn("[OSS 资源签名失败] ref={}", value, ex);
            return value;
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    public String normalizeStorageReference(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (!properties.isConfigured()) {
            return value;
        }

        String objectKey = extractResolvableObjectKey(value);
        if (!StringUtils.hasText(objectKey)) {
            return value;
        }
        return buildStorageReference(objectKey);
    }

    private String trimSlashes(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String result = value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String extractResolvableObjectKey(String value) {
        if (isStorageReference(value)) {
            return extractObjectKey(value);
        }
        return extractLegacyUrlObjectKey(value);
    }

    private String extractLegacyUrlObjectKey(String value) {
        try {
            URI uri = new URI(value.trim());
            String host = uri.getHost();
            if (!StringUtils.hasText(host) || !matchesConfiguredHost(host)) {
                return null;
            }
            return trimSlashes(uri.getPath());
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean matchesConfiguredHost(String host) {
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalizedHost)) {
            return false;
        }

        String configuredPublicHost = extractHost(properties.getPublicBaseUrl());
        if (StringUtils.hasText(configuredPublicHost)
                && normalizedHost.equals(configuredPublicHost.toLowerCase(Locale.ROOT))) {
            return true;
        }

        String endpointHost = extractHost(properties.getEndpoint());
        if (!StringUtils.hasText(endpointHost) || !StringUtils.hasText(properties.getBucket())) {
            return false;
        }
        String bucketHost = (properties.getBucket().trim() + "." + endpointHost).toLowerCase(Locale.ROOT);
        return normalizedHost.equals(bucketHost);
    }

    private String extractHost(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String candidate = value.trim();
        try {
            URI uri = new URI(candidate.contains("://") ? candidate : "https://" + candidate);
            return uri.getHost();
        } catch (Exception ex) {
            return "";
        }
    }
}
