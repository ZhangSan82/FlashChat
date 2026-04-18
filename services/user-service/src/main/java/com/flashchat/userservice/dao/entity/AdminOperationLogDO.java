package com.flashchat.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员操作日志。
 * <p>
 * 第一版先保证关键管理动作全部留痕，便于后续做后台查询与审计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_admin_operation_log")
public class AdminOperationLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 操作人账号主键，对应 t_account.id。
     */
    private Long operatorId;

    /**
     * 操作人业务账号 ID 快照，例如 FC-XXXXXX。
     */
    private String operatorAccountId;

    /**
     * 操作类型，取值见 {@code AdminOperationTypeEnum}。
     */
    private String operationType;

    /**
     * 操作对象类型，取值见 {@code AdminOperationTargetTypeEnum}。
     */
    private String targetType;

    /**
     * 操作对象标识。
     * 账号场景存 accountId，房间场景存 roomId。
     */
    private String targetId;

    /**
     * 操作对象展示文案快照，便于审计查询时快速阅读。
     */
    private String targetDisplay;

    /**
     * 操作原因。
     */
    private String reason;

    /**
     * 额外详情，使用 JSON 字符串承载。
     */
    private String detailJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;
}
