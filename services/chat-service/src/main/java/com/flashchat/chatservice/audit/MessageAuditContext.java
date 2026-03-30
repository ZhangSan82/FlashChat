package com.flashchat.chatservice.audit;

import com.flashchat.chatservice.dto.msg.FileDTO;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息审核上下文 — 贯穿整条审核责任链
 */
@Getter
public class MessageAuditContext {


    private final String content;
    private final List<FileDTO> files;
    private final String roomId;
    private final Long senderId;
    private final Integer msgType;
    private AuditResultEnum result;
    private String modifiedContent;
    private String rejectReason;

    private final List<AuditTrace> traces = new ArrayList<>();


    private MessageAuditContext(String content, List<FileDTO> files,
                                String roomId, Long senderId, Integer msgType) {
        this.content = content;
        this.files = files;
        this.roomId = roomId;
        this.senderId = senderId;
        this.msgType = msgType;
        // result / modifiedContent / rejectReason 保持 null
    }

    /**
     * 静态工厂方法 — 唯一的创建入口
     * 只接受输入字段，输出字段初始为 null，由审核链在执行过程中填充。
     */
    public static MessageAuditContext of(String content, List<FileDTO> files,
                                         String roomId, Long senderId,
                                         Integer msgType) {
        return new MessageAuditContext(content, files, roomId, senderId, msgType);
    }


    /**
     * 获取当前有效内容
     * <p>
     * 优先返回替换后内容（支持多 Handler 叠加替换），
     * 没有替换过则返回原始内容。
     * <p>
     * <b>Handler 内部必须通过此方法读取内容</b>，不要直接读 content 字段。
     */
    public String getEffectiveContent() {
        return modifiedContent != null ? modifiedContent : content;
    }

    /**
     * 审核是否被拒绝
     */
    public boolean isRejected() {
        return result == AuditResultEnum.REJECT;
    }

    /**
     * 内容是否被替换过
     * <p>
     * 只要 modifiedContent 有值就说明有 Handler 做过替换，
     * 不依赖 result 字段，避免后续 Handler 的 markPass() 导致替换丢失。
     */
    public boolean isReplaced() {
        return modifiedContent != null;
    }

    /**
     * 审核是否通过（未被拒绝即视为通过）
     */
    public boolean isPassed() {
        return result != AuditResultEnum.REJECT;
    }


    /**
     * 标记消息通过审核
     * <p>
     * 仅当 result 还是 null（尚未有任何 Handler 做过判断）时才设为 PASS。
     * 已有 REJECT 或 REPLACE 结论的不会被覆盖，保护前序 Handler 的决策。
     */
    public void markPass() {
        if (this.result == null) {
            this.result = AuditResultEnum.PASS;
        }
    }

    /**
     * 标记消息被拒绝
     * <p>
     * REJECT 是最高优先级，一旦标记后不可被任何操作覆盖。
     */
    public void markReject(String reason) {
        this.result = AuditResultEnum.REJECT;
        this.rejectReason = reason;
    }

    /**
     * 替换消息内容
     * <p>
     * 支持叠加：后调用的 Handler 覆盖 modifiedContent，
     * 后续 Handler 通过 getEffectiveContent() 读到最新的替换结果。
     */
    public void markReplace(String newContent) {
        if (this.result != AuditResultEnum.REJECT) {
            this.result = AuditResultEnum.REPLACE;
            this.modifiedContent = newContent;
        }
    }

    /**
     * 添加审计追踪记录
     */
    public void addTrace(AuditTrace trace) {
        this.traces.add(trace);
    }
}