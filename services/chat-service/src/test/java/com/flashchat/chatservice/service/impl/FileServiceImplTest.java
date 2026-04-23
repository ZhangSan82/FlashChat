package com.flashchat.chatservice.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.flashchat.chatservice.config.FileStorageProperties;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.chatservice.service.oss.OssClientFactory;
import com.flashchat.convention.storage.OssAssetUrlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private OssClientFactory ossClientFactory;

    @Mock
    private OSS ossClient;

    @Mock
    private OssAssetUrlService ossAssetUrlService;

    @Test
    void initShouldFailWhenOssConfigurationIsIncomplete() {
        FileStorageProperties properties = new FileStorageProperties();
        FileServiceImpl service = new FileServiceImpl(properties, ossClientFactory, ossAssetUrlService);

        assertThatThrownBy(service::init)
                .hasMessageContaining("OSS");
    }

    @Test
    void uploadShouldSendFileToOssWhenConfigured() {
        FileStorageProperties properties = ossProperties();
        when(ossClientFactory.createClient(properties.getOss())).thenReturn(ossClient);
        when(ossAssetUrlService.buildStorageReference(any())).thenReturn("oss://flashchat/2026/04/22/test.zip");
        when(ossAssetUrlService.resolveAccessUrl("oss://flashchat/2026/04/22/test.zip"))
                .thenReturn("https://signed.example.com/flashchat/2026/04/22/test.zip");

        FileServiceImpl service = new FileServiceImpl(properties, ossClientFactory, ossAssetUrlService);
        service.init();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "package.zip",
                "application/zip",
                "flashchat".getBytes(StandardCharsets.UTF_8)
        );

        FileDTO result = service.upload(file);

        ArgumentCaptor<ObjectMetadata> metadataCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(ossClient).putObject(
                eq("demo-bucket"),
                startsWith("flashchat/"),
                any(InputStream.class),
                metadataCaptor.capture()
        );
        verify(ossClient).shutdown();

        assertThat(result.getUrl()).isEqualTo("oss://flashchat/2026/04/22/test.zip");
        assertThat(result.getPreview()).isNull();
        assertThat(metadataCaptor.getValue().getContentType()).isEqualTo("application/zip");
        assertThat(metadataCaptor.getValue().getContentLength()).isEqualTo(file.getSize());
    }

    @Test
    void uploadShouldRejectAudioFileWhenTypeIsNotAllowed() {
        FileStorageProperties properties = ossProperties();
        FileServiceImpl service = new FileServiceImpl(properties, ossClientFactory, ossAssetUrlService);
        service.init();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "voice.mp3",
                "audio/mpeg",
                "flashchat".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.upload(file))
                .hasMessageContaining("不支持的文件类型");
    }

    private FileStorageProperties ossProperties() {
        FileStorageProperties properties = new FileStorageProperties();
        FileStorageProperties.OssProperties oss = new FileStorageProperties.OssProperties();
        oss.setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        oss.setBucket("demo-bucket");
        oss.setObjectPrefix("flashchat");
        oss.setAccessKeyId("test-ak");
        oss.setAccessKeySecret("test-sk");
        properties.setOss(oss);
        return properties;
    }
}
