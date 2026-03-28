package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 积分流水查询响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditTransactionRespDTO {

    /** 流水 ID */
    private Long id;

    /** 变动数量（正=收入，负=支出） */
    private Integer amount;

    /** 变动类型描述（如"注册奖励"、"创建房间消费"） */
    private String typeDesc;

    /** 变动类型编码 */
    private String type;

    /** 业务关联 ID */
    private String bizId;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;
}