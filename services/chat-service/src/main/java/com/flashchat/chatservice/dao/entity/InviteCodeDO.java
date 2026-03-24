package com.flashchat.chatservice.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 邀请码
 * 对应表：t_invite_code
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_invite_code")
public class InviteCodeDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邀请码 */
    private String code;

    /** 持有者（t_account.id，种子码为 NULL） */
    private Long ownerId;

    /** 使用者（t_account.id） */
    private Long usedBy;

    /** 0=未使用 1=已使用 */
    private Integer status;

    private LocalDateTime createTime;
}