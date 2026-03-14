package com.flashchat.chatservice.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 匿名成员（持久身份）
 * 对应表：t_member
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_member")
public class MemberDO {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 全局唯一账号 ID（如 FC-8A3D7K）
     */
    private String accountId;

    /**
     * 密码（BCrypt 加密）
     * 空字符串 = 未设置
     */
    private String password;

    /**
     * 昵称（首次随机生成，如"神秘的猫咪"）
     * 全局统一，修改后所有房间立即生效
     */
    private String nickname;

    /**
     * 头像背景色（如 #FF6B6B）
     * 纯色背景 + 昵称首字
     */
    private String avatarColor;

    /**
     * 最近活跃时间
     */
    private LocalDateTime lastActiveTime;

    /**
     * 状态：0-封禁 1-正常
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
}