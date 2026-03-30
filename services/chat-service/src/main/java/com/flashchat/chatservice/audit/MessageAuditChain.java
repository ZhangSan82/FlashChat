package com.flashchat.chatservice.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 消息审核责任链编排器
 * <p>
 * 职责：按 {@code @Order} 顺序逐个调用所有 {@link AbstractAuditHandler}。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>开闭原则：新增 Handler 只需实现 AbstractAuditHandler + @Component + @Order，
 *       链自动包含，无需修改此类</li>
 *   <li>短路终止：任一 Handler 标记 REJECT 后，后续 Handler 跳过（由 AbstractAuditHandler 模板方法保证）</li>
 *   <li>异常隔离：单个 Handler 异常不影响其他 Handler 和整条链（由模板方法的 try-catch 保证）</li>
 * </ul>
 * <p>
 */
@Slf4j
@Component
public class MessageAuditChain {

    private final List<AbstractAuditHandler> handlers;

    /**
     * Spring 自动注入所有 AbstractAuditHandler 的实现 Bean
     * <p>
     * 按 @Order 值升序排列（值越小越先执行）。
     * required = false：没有任何 Handler Bean 时不报错，handlers 为空列表。
     */
    @Autowired
    public MessageAuditChain(
            @Autowired(required = false) List<AbstractAuditHandler> handlers) {
        this.handlers = (handlers != null) ? handlers : Collections.emptyList();

        if (this.handlers.isEmpty()) {
            log.info("[审核链] 初始化完成, 无审核节点（链为空，所有消息放行）");
        } else {
            log.info("[审核链] 初始化完成, {} 个审核节点: {}",
                    this.handlers.size(),
                    this.handlers.stream()
                            .map(h -> h.getClass().getSimpleName())
                            .toList());
        }
    }

    /**
     * 执行审核链
     * <p>
     * 链执行完毕后，通过 context 的方法判断结果：
     * <ul>
     *   <li>{@code context.isRejected()} → 消息被拒绝，读 rejectReason 通知用户</li>
     *   <li>{@code context.isReplaced()} → 内容被替换，读 effectiveContent 作为新内容</li>
     *   <li>{@code context.isPassed()} → 正常通过</li>
     * </ul>
     * <p>
     * 链本身不抛异常（每个 Handler 的异常由模板方法内部处理）。
     *
     * @param context 审核上下文（不可为 null）
     */
    public void execute(MessageAuditContext context) {
        if (handlers.isEmpty()) {
            return;
        }
        long chainStart = System.currentTimeMillis();
        for (AbstractAuditHandler handler : handlers) {
            handler.handle(context);
            // 短路：REJECT 后不再执行后续 Handler
            // 虽然 AbstractAuditHandler.handle() 内部已做跳过判断，
            // 但在链层面也做短路，避免无谓的方法调用开销
            if (context.isRejected()) {
                break;
            }
        }

        long chainCost = System.currentTimeMillis() - chainStart;
        // 链级别日志
        if (context.isRejected()) {
            log.info("[审核链] 拒绝, roomId={}, senderId={}, reason={}, 耗时={}ms, traces={}",
                    context.getRoomId(), context.getSenderId(),
                    context.getRejectReason(), chainCost,
                    formatTraces(context.getTraces()));
        } else if (log.isDebugEnabled()) {
            log.debug("[审核链] 通过, roomId={}, senderId={}, replaced={}, 耗时={}ms",
                    context.getRoomId(), context.getSenderId(),
                    context.isReplaced(), chainCost);
        }
    }

    /**
     * 格式化 traces 用于日志输出
     * <p>
     * 示例：[ContentLength:PASS:0ms, SensitiveWord:REPLACE:1ms, ...]
     */
    private String formatTraces(List<AuditTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < traces.size(); i++) {
            AuditTrace t = traces.get(i);
            if (i > 0) sb.append(", ");
            sb.append(t.getHandlerName().replace("AuditHandler", ""))
                    .append(":")
                    .append(t.getResult() != null ? t.getResult().name() : "SKIP")
                    .append(":")
                    .append(t.getCostMs()).append("ms");
        }
        sb.append("]");
        return sb.toString();
    }
}
