package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dto.context.FileSecurityConstants;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.chatservice.service.FileService;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件上传服务 — 本地磁盘存储实现
 * 存储规则：
 *   存储路径：{upload-path}/{yyyy/MM/dd}/{uuid}.{ext}
 *   访问URL：{url-prefix}/{yyyy/MM/dd}/{uuid}.{ext}
 * 安全措施：
 *   1. UUID 重命名：彻底消除路径穿越风险（../../etc/passwd）
 *      原始文件名只在 FileDTO.name 中返回给前端展示，不用于存储
 *   2. 黑名单后缀拦截：引用 FileSecurityConstants 公共常量
 *      与 FileMsgHandler 共用同一份黑名单，一处定义避免不同步
 *   3. 文件大小：由 Spring multipart 配置限制（application.yaml）
 * 写入方式：
 *   使用 Files.copy + InputStream 而不是 MultipartFile.transferTo
 *   原因：transferTo 依赖 Servlet 容器的临时文件机制，
 *         某些嵌入式 Tomcat 场景下临时文件可能提前被清理导致失败
 *         Files.copy 是 NIO 原生方法，更可靠
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    /**
     * 文件存储根路径
     * 默认：./uploads（相对于应用工作目录）
     * 生产环境建议配置为绝对路径（如 /data/flashchat/uploads）
     */
    @Value("${flashchat.file.upload-path:./uploads}")
    private String uploadPath;

    /**
     * 文件访问 URL 前缀
     * 默认：http://localhost:8081/uploads
     * 生产环境配置为 Nginx 域名（如 https://files.flashchat.com/uploads）
     */
    @Value("${flashchat.file.url-prefix:http://localhost:8081/uploads}")
    private String urlPrefix;

    /** 日期目录格式 */
    private static final DateTimeFormatter DATE_DIR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 启动时创建上传根目录
     */
    @PostConstruct
    public void init() {
        try {
            Path basePath = Paths.get(uploadPath).toAbsolutePath().normalize();
            Files.createDirectories(basePath);
            log.info("[文件服务] 初始化完成, 存储路径={}, URL前缀={}", basePath, urlPrefix);
        } catch (IOException e) {
            log.error("[文件服务] 创建上传目录失败: {}", uploadPath, e);
            throw new ServiceException("文件服务初始化失败");
        }
    }

    @Override
    public FileDTO upload(MultipartFile file) {
        // ===== 1. 校验 =====
        if (file.isEmpty()) {
            throw new ClientException("上传文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unnamed";
        }

        if (FileSecurityConstants.isDangerousFile(originalName)) {
            throw new ClientException("不支持的文件类型: " + originalName);
        }

        String contentType = file.getContentType();

        // ===== 2. 生成存储路径 =====
        String extension = FileSecurityConstants.getExtension(originalName);
        if (extension.isEmpty()) {
            extension = inferExtensionFromContentType(contentType);
            if (!extension.isEmpty()) {
                originalName = originalName + extension;
            }
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        String datePath = LocalDate.now().format(DATE_DIR_FORMAT);

        try {
            // ===== 3. 创建日期目录 =====
            Path dirPath = Paths.get(uploadPath, datePath).toAbsolutePath().normalize();
            Files.createDirectories(dirPath);

            // ===== 4. 写入磁盘（NIO Files.copy，不依赖 Servlet 临时文件） =====
            Path filePath = dirPath.resolve(storedName);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            // ===== 5. 构建返回值 =====
            String normalizedPrefix = urlPrefix.endsWith("/")
                    ? urlPrefix.substring(0, urlPrefix.length() - 1)
                    : urlPrefix;
            String url = normalizedPrefix + "/" + datePath + "/" + storedName;

            // 图片文件：preview 设为与 url 相同（vue-advanced-chat 用 preview 做缩略图）
            // 视频文件：preview 暂为 null（未来 Phase 2 可用 FFmpeg 生成封面）
            String preview = null;
            if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
                preview = url;
            }

            // audio 和 duration 不在上传接口设置：
            //   上传接口不知道文件的用途（可能是语音也可能是音乐附件）
            //   前端在发消息前根据 HTML5 Audio/Video API 获取 duration 并补充
            //   VoiceMsgHandler.enrichFiles() 会兜底补 audio=true
            FileDTO result = FileDTO.builder()
                    .name(originalName)
                    .size(file.getSize())
                    .type(contentType != null ? contentType : "application/octet-stream")
                    .url(url)
                    .preview(preview)
                    .build();

            log.info("[文件上传成功] original={}, stored={}, size={}, type={}, url={}",
                    originalName, storedName, file.getSize(), contentType, url);

            return result;

        } catch (IOException e) {
            log.error("[文件上传失败] name={}, error={}", originalName, e.getMessage(), e);
            throw new ServiceException("文件上传失败，请重试");
        }
    }

    private String inferExtensionFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        String normalized = contentType.toLowerCase();
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "image/heic" -> ".heic";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "audio/mpeg" -> ".mp3";
            case "audio/wav", "audio/wave", "audio/x-wav" -> ".wav";
            case "audio/webm" -> ".webm";
            case "audio/ogg" -> ".ogg";
            case "application/pdf" -> ".pdf";
            case "application/zip" -> ".zip";
            case "text/plain" -> ".txt";
            default -> "";
        };
    }
}
