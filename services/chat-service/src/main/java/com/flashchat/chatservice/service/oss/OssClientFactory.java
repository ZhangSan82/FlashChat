package com.flashchat.chatservice.service.oss;

import com.aliyun.oss.OSS;
import com.flashchat.chatservice.config.FileStorageProperties;

/**
 * OSS Client 工厂。
 * 单独抽出来是为了隔离 SDK 创建细节，并方便单元测试替换成 mock。
 */
public interface OssClientFactory {

    OSS createClient(FileStorageProperties.OssProperties properties);
}
