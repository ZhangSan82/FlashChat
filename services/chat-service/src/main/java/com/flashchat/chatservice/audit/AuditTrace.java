package com.flashchat.chatservice.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个审核节点的执行记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditTrace {

    /**
     * Handler 名称
     */
    private String handlerName;

    /**
     * 该节点的审核结论
     * null 表示该 Handler 未做判断（如被跳过）
     */
    private AuditResultEnum result;

    /**
     * 执行耗时（毫秒）
     */
    private long costMs;

    /**
     * 审核详情（脱敏）
     * <p>
     * 示例：
     * <ul>
     *   <li>"跳过(Handler已禁用)"</li>
     *   <li>"跳过(前序已REJECT)"</li>
     *   <li>"通过"</li>
     *   <li>"命中敏感词分类:辱骂, 替换2处"</li>
     *   <li>"拒绝:命中敏感词分类:政治敏感"</li>
     *   <li>"异常:ReadTimeoutException, 按fail-open放行"</li>
     * </ul>
     * <p>
     * <b>绝不记录</b>用户消息原文或具体命中的敏感词原文。
     */
    private String detail;
}
