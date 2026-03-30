package com.flashchat.chatservice.audit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * 消息审核配置
 */

@Data
@ConfigurationProperties(prefix = "flashchat.audit")
public class AuditProperties {

    /**
     * 审核总开关
     * false 时所有 Handler 的 isEnabled() 返回 false，整条链跳过
     */
    private boolean enabled = true;

    /**
     * 内容长度审核配置
     */
    private ContentLengthConfig contentLength = new ContentLengthConfig();

    /**
     * 敏感词审核配置
     */
    private SensitiveWordConfig sensitiveWord = new SensitiveWordConfig();

    // ==================== 内容长度 ====================

    @Data
    public static class ContentLengthConfig {

        private boolean enabled = true;

        /**
         * 最小长度（去除首尾空白后）
         * 低于此值拒绝（纯空白消息防御）
         */
        private int minLength = 1;

        /**
         * 最大长度
         */
        private int maxLength = 500;
    }

    // ==================== 敏感词 ====================

    @Data
    public static class SensitiveWordConfig {

        private boolean enabled = true;

        /**
         * 词库文件路径
         * 支持 classpath: 和 file: 前缀
         * 文件格式：每行一个词条，# 开头为注释，空行跳过
         * 词条格式：词语[,分类[,级别]]
         *   级别 REJECT = 直接拒绝（默认）
         *   级别 REPLACE = 替换为 ***
         * 示例：
         *   脏话,辱骂,REPLACE
         *   政治敏感词,政治,REJECT
         *   普通敏感词
         */
        private String dictPath = "classpath:audit/sensitive-words.txt";

        /**
         * 词库热更新间隔（毫秒）
         * 0 或负数表示不自动更新
         */
        private long reloadIntervalMs = 300_000;

        /**
         * 替换字符
         */
        private String replaceChar = "*";

        /**
         * 默认命中策略（词条未指定级别时使用）
         * REJECT = 拒绝发送
         * REPLACE = 替换后放行
         */
        private String defaultStrategy = "REPLACE";
    }
}