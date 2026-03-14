package com.flashchat.chatservice.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 注册用户（主持人）
 * 对应表：t_user
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user")
public class UserDO {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名（房间内显示名，全局唯一）
     */
    private String username;

    /**
     * 密码（BCrypt 加密）
     */
    private String password;

    /**
     * 昵称（预留，暂与 username 一致）
     */
    private String nickname;

    /**
     * 头像 URL
     */
    private String avatarUrl;

    /**
     * 自己的邀请码（注册时系统生成，全局唯一）
     */
    private String inviteCode;

    /**
     * 邀请人用户 ID（关系链追踪）
     * NULL 表示通过其他方式注册（如管理员手动创建）
     */
    private Long invitedBy;

    /**
     * 积分余额
     */
    private Integer credits;

    /**
     * 状态：0-禁用 1-正常
     */
    private Integer status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除 1-已删除
     */
    @TableLogic
    private Integer delFlag;
}