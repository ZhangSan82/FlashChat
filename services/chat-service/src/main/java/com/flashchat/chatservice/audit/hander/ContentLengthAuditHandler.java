package com.flashchat.chatservice.audit.hander;

import com.flashchat.chatservice.audit.AbstractAuditHandler;
import com.flashchat.chatservice.audit.AuditProperties;
import com.flashchat.chatservice.audit.MessageAuditContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 内容长度审核 Handler
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
@EnableConfigurationProperties(AuditProperties.class)
public class ContentLengthAuditHandler extends AbstractAuditHandler {
    private final AuditProperties auditProperties;
    @Override
    protected boolean isEnabled() {
        return auditProperties.isEnabled()
                && auditProperties.getContentLength().isEnabled();
    }
    @Override
    protected void doAudit(MessageAuditContext context) {
        String content = context.getEffectiveContent();
        // 纯文件消息：content 可能为 null 或空，但有 files → 跳过长度检查
        if ((content == null || content.isBlank())
                && context.getFiles() != null && !context.getFiles().isEmpty()) {
            return;
        }
        if (content == null || content.isBlank()) {
            context.markReject("消息内容不能为空");
            return;
        }
        AuditProperties.ContentLengthConfig config = auditProperties.getContentLength();
        // trim 后检查有效长度
        String trimmed = content.trim();
        if (trimmed.length() < config.getMinLength()) {
            context.markReject("消息内容过短");
            return;
        }
        if (trimmed.length() > config.getMaxLength()) {
            context.markReject("消息内容超过" + config.getMaxLength() + "字限制");
            return;
        }
        // 无问题放行
    }
}