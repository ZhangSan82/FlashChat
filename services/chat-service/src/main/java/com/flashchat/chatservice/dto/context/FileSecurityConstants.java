package com.flashchat.chatservice.dto.context;

import java.util.Locale;
import java.util.Set;

/**
 * 文件安全常量。
 * 当前系统仅允许三类附件：
 * 1. 图片
 * 2. 视频
 * 3. 压缩包
 *
 * 这套规则会同时用于上传接口和消息发送校验，避免不同入口出现口径不一致。
 */
public final class FileSecurityConstants {

    private FileSecurityConstants() {
    }

    /**
     * 危险后缀黑名单。
     * 即使后续白名单误配，这些可执行/脚本类后缀也必须明确拒绝。
     */
    public static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".jsp", ".jspx", ".asp", ".aspx",
            ".sh", ".bash", ".csh",
            ".exe", ".bat", ".cmd", ".com", ".msi",
            ".ps1", ".psm1",
            ".php", ".phtml",
            ".py", ".pyc", ".pyo",
            ".rb", ".pl",
            ".class", ".jar", ".war", ".ear",
            ".dll", ".so",
            ".vbs", ".wsf", ".hta"
    );

    public static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif", ".avif"
    );

    public static final Set<String> ALLOWED_VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".webm", ".mov", ".m4v"
    );

    public static final Set<String> ALLOWED_ARCHIVE_EXTENSIONS = Set.of(
            ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz"
    );

    public static final Set<String> ALLOWED_ARCHIVE_MIME_TYPES = Set.of(
            "application/zip",
            "application/x-zip-compressed",
            "multipart/x-zip",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/gzip",
            "application/x-gzip",
            "application/x-tar",
            "application/x-bzip2",
            "application/x-xz"
    );

    public static boolean isDangerousFile(String fileName) {
        String extension = getExtension(fileName);
        return !extension.isEmpty() && BLOCKED_EXTENSIONS.contains(extension);
    }

    /**
     * 是否为允许上传/发送的文件类型。
     * 优先相信 MIME type；当 MIME type 缺失时，再退回扩展名判断。
     */
    public static boolean isAllowedFileType(String fileName, String contentType) {
        if (isDangerousFile(fileName)) {
            return false;
        }

        String normalizedType = normalizeContentType(contentType);
        if (!normalizedType.isEmpty()) {
            if (normalizedType.startsWith("image/")) {
                return true;
            }
            if (normalizedType.startsWith("video/")) {
                return true;
            }
            return ALLOWED_ARCHIVE_MIME_TYPES.contains(normalizedType);
        }

        String extension = getExtension(fileName);
        return ALLOWED_IMAGE_EXTENSIONS.contains(extension)
                || ALLOWED_VIDEO_EXTENSIONS.contains(extension)
                || ALLOWED_ARCHIVE_EXTENSIONS.contains(extension);
    }

    public static String getExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    public static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }
}
