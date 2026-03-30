package com.flashchat.chatservice.audit;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核结论枚举
 */
@Getter
@AllArgsConstructor
public enum AuditResultEnum {

    /**
     * 通过，消息正常发送
     */
    PASS("通过"),

    /**
     * 拒绝，消息不发送，通知发送者
     */
    REJECT("拒绝"),

    /**
     * 内容替换后放行
     * <p>
     * 如敏感词替换为 ***，脱敏手机号等。
     * 多个 Handler 可叠加替换。
     */
    REPLACE("替换");

    private final String desc;
}