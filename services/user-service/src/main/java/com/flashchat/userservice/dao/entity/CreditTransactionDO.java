package com.flashchat.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 积分流水
 * <p>
 * 对应表：t_credit_transaction
 * <p>
 * 核心字段说明：
 * <ul>
 *   <li>amount：正数=收入，负数=支出。由 CreditService 内部设置正负号，调用方传正数</li>
 *   <li>balanceAfter：变动后余额，仅供审计参考，并发下可能不精确，不做业务判断</li>
 *   <li>idempotentKey：幂等键，格式为 {CreditTypeEnum.name()}:{bizId}，唯一索引保证不重复</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_credit_transaction")
public class CreditTransactionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID（t_account.id）
     */
    private Long accountId;

    /**
     * 变动数量
     */
    private Integer amount;

    /**
     * 变动后余额（审计参考）

     */
    private Integer balanceAfter;

    /**
     * 变动类型（CreditTypeEnum 枚举名）
     */
    private String type;

    /**
     * 业务关联 ID
     */
    private String bizId;

    /**
     * 幂等键
     */
    private String idempotentKey;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
