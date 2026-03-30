package com.flashchat.chatservice.audit;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 审核节点抽象基类 — 模板方法模式
 * AuditTrace 记录原则：
 * trace.result 和 trace.detail 反映的是<b>当前 Handler 自身的操作</b>，
 * 不是 Context 的累积状态。通过 doAudit() 前后快照 Context 状态来推断。
 */
@Slf4j
public abstract class AbstractAuditHandler {

    /**
     * 模板方法
     */
    public final void handle(MessageAuditContext context) {
        String handlerName = getHandlerName();
        // ===== 1. Handler 未启用 -> 跳过 =====
        if (!isEnabled()) {
            context.addTrace(AuditTrace.builder()
                    .handlerName(handlerName)
                    .result(null)
                    .costMs(0)
                    .detail("跳过(Handler已禁用)")
                    .build());
            return;
        }
        // ===== 2. 前序已 REJECT -> 短路跳过 =====
        if (context.isRejected()) {
            context.addTrace(AuditTrace.builder()
                    .handlerName(handlerName)
                    .result(null)
                    .costMs(0)
                    .detail("跳过(前序已REJECT)")
                    .build());
            return;
        }

        // ===== 3. 快照 doAudit() 前的状态，用于对比推断当前 Handler 的操作 =====
        AuditResultEnum beforeResult = context.getResult();
        String beforeModified = context.getModifiedContent();
        long start = System.currentTimeMillis();
        AuditResultEnum handlerResult = null;
        String detail = "";
        try {
            // ===== 4. 执行子类审核逻辑 =====
            doAudit(context);
            // ===== 5. 对比前后状态，推断当前 Handler 做了什么 =====
            handlerResult = inferHandlerResult(beforeResult, beforeModified, context);
            detail = buildNormalDetail(beforeResult, beforeModified, context);

        } catch (Exception e) {
            // ===== 异常兜底 =====
            if (isFailClose()) {
                context.markReject("系统审核异常，请稍后重试");
                handlerResult = AuditResultEnum.REJECT;
                detail = "异常:" + e.getClass().getSimpleName() + ", 按fail-close拒绝";
                log.error("[审核] {} 执行异常, fail-close拒绝, roomId={}, senderId={}",
                        handlerName, context.getRoomId(), context.getSenderId(), e);
            } else {
                handlerResult = null;
                detail = "异常:" + e.getClass().getSimpleName() + ", 按fail-open放行";
                log.error("[审核] {} 执行异常, fail-open放行, roomId={}, senderId={}",
                        handlerName, context.getRoomId(), context.getSenderId(), e);
            }
        } finally {
            long costMs = System.currentTimeMillis() - start;
            context.addTrace(AuditTrace.builder()
                    .handlerName(handlerName)
                    .result(handlerResult)
                    .costMs(costMs)
                    .detail(detail)
                    .build());
        }
    }
    // ==================== 子类必须实现 ====================

    /**
     * 执行审核逻辑
     */
    protected abstract void doAudit(MessageAuditContext context);

    // ==================== 子类可覆写 ====================

    /**
     * 该 Handler 是否启用（默认启用，子类可从配置读取）
     */
    protected boolean isEnabled() {
        return true;
    }

    /**
     * 异常时是否拒绝（默认 false = fail-open）
     */
    protected boolean isFailClose() {
        return false;
    }

    // ==================== 内部方法 ====================

    protected String getHandlerName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 推断当前 Handler 的操作结果
     * <p>
     * 通过对比 doAudit() 前后 Context 的状态变化来推断，
     * 确保 trace 记录的是 Handler 自身的行为，不是累积状态。
     */
    private AuditResultEnum inferHandlerResult(AuditResultEnum beforeResult,
                                               String beforeModified,
                                               MessageAuditContext context) {
        // 状态没有任何变化 → 该 Handler 未做判断
        if (context.getResult() == beforeResult
                && Objects.equals(context.getModifiedContent(), beforeModified)) {
            return null;
        }
        // 新增了 REJECT
        if (context.isRejected() && beforeResult != AuditResultEnum.REJECT) {
            return AuditResultEnum.REJECT;
        }
        // 新增或追加了 REPLACE（modifiedContent 发生变化）
        if (!Objects.equals(context.getModifiedContent(), beforeModified)) {
            return AuditResultEnum.REPLACE;
        }
        // result 从 null 变为 PASS
        if (context.getResult() == AuditResultEnum.PASS && beforeResult == null) {
            return AuditResultEnum.PASS;
        }
        return context.getResult();
    }

    /**
     * 构建正常执行时的 detail 描述
     * <p>
     * 基于前后状态对比，精确描述当前 Handler 做了什么。
     */
    private String buildNormalDetail(AuditResultEnum beforeResult,
                                     String beforeModified,
                                     MessageAuditContext context) {
        // 状态没变化 → 未做判断
        if (context.getResult() == beforeResult
                && Objects.equals(context.getModifiedContent(), beforeModified)) {
            return "通过(无操作)";
        }
        if (context.isRejected() && beforeResult != AuditResultEnum.REJECT) {
            String reason = context.getRejectReason();
            return "拒绝:" + (reason != null ? reason : "未说明原因");
        }
        if (!Objects.equals(context.getModifiedContent(), beforeModified)) {
            return "内容已替换";
        }
        return "通过";
    }
}
