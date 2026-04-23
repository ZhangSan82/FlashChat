package com.flashchat.chatservice.service.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.flashchat.chatservice.config.FileStorageProperties;
import org.springframework.stereotype.Component;

/**
 * 默认 OSS Client 工厂实现。
 */
@Component
public class DefaultOssClientFactory implements OssClientFactory {

    @Override
    public OSS createClient(FileStorageProperties.OssProperties properties) {
        return new OSSClientBuilder().build(
                properties.getEndpoint(),
                properties.getAccessKeyId(),
                properties.getAccessKeySecret()
        );
    }
}
