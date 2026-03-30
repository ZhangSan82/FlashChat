package com.flashchat.chatservice.audit.hander;

import com.flashchat.chatservice.audit.AbstractAuditHandler;
import com.flashchat.chatservice.audit.AuditProperties;
import com.flashchat.chatservice.audit.MessageAuditContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;

/**
 * 敏感词审核 Handler — DFA实现
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
@EnableConfigurationProperties(AuditProperties.class)
public class SensitiveWordAuditHandler extends AbstractAuditHandler {

    private final AuditProperties auditProperties;

    /**
     * DFA 自动机实例
     */
    private volatile DfaAutomaton automaton = DfaAutomaton.EMPTY;

    /**
     * 词库文件最后修改时间（用于检测是否需要重新加载）
     */
    private volatile long lastModified = 0;

    @PostConstruct
    public void init() {
        loadDict();
    }

    /**
     * 定时热更新词库
     * <p>
     * fixedDelayString 从配置读取间隔，默认 5 分钟。
     * 注意：配置值必须为正数，Spring @Scheduled 不接受 0 或负数的 fixedDelay。
     */
    @Scheduled(fixedDelayString = "${flashchat.audit.sensitive-word.reload-interval-ms:300000}",
            initialDelay = 300_000)
    public void scheduledReload() {
        if (!auditProperties.isEnabled()
                || !auditProperties.getSensitiveWord().isEnabled()) {
            return;
        }
        try {
            Resource resource = new DefaultResourceLoader()
                    .getResource(auditProperties.getSensitiveWord().getDictPath());

            long currentModified = 0;
            try {
                currentModified = resource.lastModified();
            } catch (Exception ignored) {
                // classpath 资源不支持 lastModified，每次都重建
            }
            if (currentModified > 0 && currentModified == lastModified) {
                log.debug("[敏感词] 词库未变化，跳过重载");
                return;
            }
            loadDict();
        } catch (Exception e) {
            log.error("[敏感词] 定时重载异常，保留旧词库", e);
        }
    }
    @Override
    protected boolean isEnabled() {
        return auditProperties.isEnabled()
                && auditProperties.getSensitiveWord().isEnabled()
                && automaton != DfaAutomaton.EMPTY;
    }
    @Override
    protected void doAudit(MessageAuditContext context) {
        String content = context.getEffectiveContent();
        if (content == null || content.isBlank()) {
            return;
        }
        DfaAutomaton currentAutomaton = this.automaton;
        List<MatchResult> matches = currentAutomaton.match(content);
        if (matches.isEmpty()) {
            return;
        }
        // 收集命中分类（用于日志，不进入用户可见的 rejectReason）
        String categories = matches.stream()
                .map(MatchResult::category)
                .distinct()
                .reduce((a, b) -> a + "/" + b)
                .orElse("未分类");

        // 检查是否有 REJECT 级别的命中
        boolean hasReject = matches.stream()
                .anyMatch(m -> "REJECT".equals(m.level()));
        if (hasReject) {
            log.info("[敏感词] REJECT, senderId={}, roomId={}, 命中分类={}",
                    context.getSenderId(), context.getRoomId(), categories);
            context.markReject("消息包含违规内容，无法发送");
            return;
        }
        // 全部是 REPLACE 级别 → 执行替换
        long replaceCount = matches.size();
        log.info("[敏感词] REPLACE, senderId={}, roomId={}, 命中分类={}, 替换{}处",
                context.getSenderId(), context.getRoomId(), categories, replaceCount);

        String replaced = doReplace(content, matches);
        context.markReplace(replaced);
    }

    // ==================== 词库加载 ====================

    private void loadDict() {
        AuditProperties.SensitiveWordConfig config = auditProperties.getSensitiveWord();

        try {
            Resource resource = new DefaultResourceLoader().getResource(config.getDictPath());

            if (!resource.exists()) {
                log.warn("[敏感词] 词库文件不存在: {}, Handler 将禁用", config.getDictPath());
                this.automaton = DfaAutomaton.EMPTY;
                return;
            }
            List<WordEntry> entries = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    entries.add(parseEntry(line, config.getDefaultStrategy()));
                }
            }
            if (entries.isEmpty()) {
                log.warn("[敏感词] 词库为空: {}", config.getDictPath());
                this.automaton = DfaAutomaton.EMPTY;
                return;
            }
            // 构建新 DFA，一次性替换引用（volatile 保证可见性）
            DfaAutomaton newAutomaton = new DfaAutomaton(entries);
            this.automaton = newAutomaton;

            try {
                this.lastModified = resource.lastModified();
            } catch (Exception ignored) {
            }

            log.info("[敏感词] 词库加载完成, 词条数={}, 路径={}",
                    entries.size(), config.getDictPath());

        } catch (Exception e) {
            log.error("[敏感词] 词库加载失败, 保留旧词库", e);
        }
    }

    /**
     * 解析词条行
     * 格式：词语[,分类[,级别]]
     */
    private WordEntry parseEntry(String line, String defaultStrategy) {
        String[] parts = line.split(",", 3);
        String word = parts[0].trim().toLowerCase();
        String category = parts.length > 1 ? parts[1].trim() : "未分类";
        String level = parts.length > 2 ? parts[2].trim().toUpperCase() : defaultStrategy;

        if (!"REJECT".equals(level) && !"REPLACE".equals(level)) {
            level = defaultStrategy;
        }

        return new WordEntry(word, category, level);
    }
    // ==================== 替换逻辑 ====================
    private String doReplace(String content, List<MatchResult> matches) {
        String replaceChar = auditProperties.getSensitiveWord().getReplaceChar();
        char rc = replaceChar.charAt(0);
        char[] chars = content.toCharArray();

        for (MatchResult match : matches) {
            for (int i = match.start(); i < match.end(); i++) {
                chars[i] = rc;
            }
        }

        return new String(chars);
    }
    // ==================== 数据类 ====================
    record WordEntry(String word, String category, String level) {
    }
    record MatchResult(int start, int end, String category, String level) {
    }
    // ==================== DFA 自动机 ====================

    /**
     * DFA 确定性有限自动机 — 类型安全的前缀树实现
     */
    static class DfaAutomaton {

        static final DfaAutomaton EMPTY = new DfaAutomaton(List.of());

        private final DfaNode root;
        private final int wordCount;

        DfaAutomaton(List<WordEntry> entries) {
            this.root = new DfaNode();
            int count = 0;

            for (WordEntry entry : entries) {
                if (entry.word().isEmpty()) {
                    continue;
                }

                DfaNode current = root;
                for (int i = 0; i < entry.word().length(); i++) {
                    char c = entry.word().charAt(i);
                    current = current.children.computeIfAbsent(c, k -> new DfaNode());
                }
                current.end = true;
                current.category = entry.category();
                current.level = entry.level();
                count++;
            }
            this.wordCount = count;
        }

        /**
         * 在文本中查找所有匹配的敏感词（非重叠贪婪匹配）
         */
        List<MatchResult> match(String text) {
            if (wordCount == 0 || text == null || text.isEmpty()) {
                return List.of();
            }

            List<MatchResult> results = new ArrayList<>();
            String lowerText = text.toLowerCase();
            for (int i = 0; i < lowerText.length(); i++) {
                DfaNode current = root;
                int matchEnd = -1;
                String matchCategory = null;
                String matchLevel = null;
                for (int j = i; j < lowerText.length(); j++) {
                    char c = lowerText.charAt(j);
                    DfaNode next = current.children.get(c);

                    if (next == null) {
                        break;
                    }
                    current = next;
                    if (current.end) {
                        // 贪婪匹配：记录当前匹配位置，继续往后尝试更长的词
                        matchEnd = j + 1;
                        matchCategory = current.category;
                        matchLevel = current.level;
                    }
                }
                if (matchEnd > 0) {
                    results.add(new MatchResult(i, matchEnd, matchCategory, matchLevel));
                    // 跳过已匹配的部分，避免重叠
                    i = matchEnd - 1;
                }
            }
            return results;
        }

        int wordCount() {
            return wordCount;
        }
    }

    /**
     * DFA 前缀树节点
     */
    static class DfaNode {

        /**
         * 子节点：key = 字符，value = 子节点
         */
        final Map<Character, DfaNode> children = new HashMap<>();

        /**
         * 是否为词条终止节点
         */
        boolean end = false;

        /**
         * 词条分类（仅终止节点有值）
         * 示例："辱骂"、"政治"、"色情"
         */
        String category;

        /**
         * 命中级别（仅终止节点有值）
         * "REJECT" 或 "REPLACE"
         */
        String level;
    }
}