package com.flashchat.chatservice.dto.context;

import java.util.Set;

/**
 * 文件安全常量
 * 集中定义文件安全相关的常量和工具方法
 * 被 FileServiceImpl（上传拦截）和 FileMsgHandler（发消息拦截）共同引用
 * 一处定义，两处使用，避免黑名单不同步的安全缺口
 */
public final class FileSecurityConstants {

    private FileSecurityConstants() {
    }

    /**
     * 危险文件后缀黑名单（全小写，含点号）
     * 这些后缀的文件可能在服务器上被执行，必须拦截
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

    /**
     * 检查文件名是否为危险文件
     * 提取最后一个 "." 之后的部分作为后缀，大小写不敏感
     * @param fileName 文件名（如 "report.jsp"）
     * @return true=危险文件，应拦截
     */
    public static boolean isDangerousFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dotIndex).toLowerCase();
        return BLOCKED_EXTENSIONS.contains(extension);
    }

    /**
     * 提取文件扩展名（含点号，小写）
     * "photo.jpg"  → ".jpg"
     * "file"       → ""
     * ".gitignore" → ".gitignore"
     * @param fileName 文件名
     * @return 扩展名（含点号），无扩展名返回空字符串
     */
    public static String getExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase();
    }
}
