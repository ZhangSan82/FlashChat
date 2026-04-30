package com.flashchat.chatservice.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.flashchat.chatservice.config.FileStorageProperties;
import com.flashchat.chatservice.dto.context.FileSecurityConstants;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.chatservice.service.FileService;
import com.flashchat.chatservice.service.oss.OssClientFactory;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.convention.storage.OssAssetUrlService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件上传服务。
 * 当前统一上传到阿里云 OSS，成功后返回公网可访问 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private static final DateTimeFormatter DATE_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final FileStorageProperties fileStorageProperties;
    private final OssClientFactory ossClientFactory;
    private final OssAssetUrlService ossAssetUrlService;

    @PostConstruct
    public void init() {
        FileStorageProperties.OssProperties oss = fileStorageProperties.getOss();
        if (!isOssConfigured(oss)) {
            log.warn("[文件服务] OSS 配置缺失，文件上传功能已禁用；调用 upload 时将返回 503。"
                    + " 如需启用，请在 .env 中填写 FLASHCHAT_OSS_* 环境变量");
            return;
        }
        log.info("[文件服务] 已启用阿里云 OSS 存储, bucket={}, endpoint={}",
                oss.getBucket(), oss.getEndpoint());
    }

    @Override
    public FileDTO upload(MultipartFile file) {
        validateOssConfiguration();
        if (file == null || file.isEmpty()) {
            throw new ClientException("上传文件不能为空");
        }

        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        if (!FileSecurityConstants.isAllowedFileType(originalName, file.getContentType())) {
            throw new ClientException("不支持的文件类型: " + originalName);
        }

        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType()
                : "application/octet-stream";
        String extension = resolveExtension(originalName, contentType);
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        String datePath = LocalDate.now().format(DATE_DIR_FORMAT);

        return uploadToOss(file, originalName, contentType, storedName, datePath);
    }

    private FileDTO uploadToOss(MultipartFile file,
                                String originalName,
                                String contentType,
                                String storedName,
                                String datePath) {
        FileStorageProperties.OssProperties oss = fileStorageProperties.getOss();
        String objectKey = buildObjectKey(oss.getObjectPrefix(), datePath, storedName);
        OSS ossClient = ossClientFactory.createClient(oss);

        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);
            metadata.setContentLength(file.getSize());

            ossClient.putObject(oss.getBucket(), objectKey, inputStream, metadata);

            String storageRef = ossAssetUrlService.buildStorageReference(objectKey);
            String accessUrl = ossAssetUrlService.resolveAccessUrl(storageRef);
            FileDTO result = buildResult(originalName, file.getSize(), contentType, storageRef, accessUrl);
            log.info("[文件上传成功] original={}, objectKey={}, bucket={}",
                    originalName, objectKey, oss.getBucket());
            return result;
        } catch (IOException e) {
            log.error("[文件上传失败] name={}, objectKey={}", originalName, objectKey, e);
            throw new ServiceException("文件上传失败，请稍后重试");
        } finally {
            ossClient.shutdown();
        }
    }

    private FileDTO buildResult(String originalName, long size, String contentType,
                                String storageRef, String accessUrl) {
        String preview = contentType.toLowerCase().startsWith("image/") ? accessUrl : null;
        return FileDTO.builder()
                .name(originalName)
                .size(size)
                .type(contentType)
                .url(storageRef)
                .preview(preview)
                .build();
    }

    private boolean isOssConfigured(FileStorageProperties.OssProperties oss) {
        return StringUtils.hasText(oss.getEndpoint())
                && StringUtils.hasText(oss.getBucket())
                && StringUtils.hasText(oss.getAccessKeyId())
                && StringUtils.hasText(oss.getAccessKeySecret());
    }

    private void validateOssConfiguration() {
        if (!isOssConfigured(fileStorageProperties.getOss())) {
            throw new ServiceException("OSS 存储配置不完整，请检查 endpoint、bucket 和访问凭证");
        }
    }

    private String sanitizeOriginalName(String originalName) {
        if (!StringUtils.hasText(originalName)) {
            return "unnamed";
        }
        return Paths.get(originalName).getFileName().toString();
    }

    private String resolveExtension(String originalName, String contentType) {
        String extension = FileSecurityConstants.getExtension(originalName);
        if (StringUtils.hasText(extension)) {
            return extension;
        }
        return inferExtensionFromContentType(contentType);
    }

    private String buildObjectKey(String objectPrefix, String datePath, String storedName) {
        String normalizedPrefix = trimSlashes(objectPrefix);
        if (!StringUtils.hasText(normalizedPrefix)) {
            return datePath + "/" + storedName;
        }
        return normalizedPrefix + "/" + datePath + "/" + storedName;
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

    private String inferExtensionFromContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        String normalized = contentType.toLowerCase();
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "image/heic", "image/x-heic" -> ".heic";
            case "image/heif", "image/x-heif" -> ".heif";
            case "image/avif" -> ".avif";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "application/zip" -> ".zip";
            case "application/x-zip-compressed", "multipart/x-zip" -> ".zip";
            case "application/x-rar-compressed", "application/vnd.rar" -> ".rar";
            case "application/x-7z-compressed" -> ".7z";
            case "application/gzip", "application/x-gzip" -> ".gz";
            case "application/x-tar" -> ".tar";
            case "application/x-bzip2" -> ".bz2";
            case "application/x-xz" -> ".xz";
            default -> "";
        };
    }
}
