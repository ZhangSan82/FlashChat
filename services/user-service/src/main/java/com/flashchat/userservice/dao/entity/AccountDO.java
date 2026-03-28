package com.flashchat.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 所有成员（持久身份）
 * 对应表：t_account
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_account")
public class AccountDO {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 全局唯一账号 ID（如 FC-8A3D7K）
     * <p>
     * 由系统自动生成，不可修改。
     * 匿名用户和注册用户都有，是贯穿整个生命周期的唯一标识。
     * 用于登录（匿名用户）、前端展示（默认遮掩为 FC-****7K）
     */
    private String accountId;

    /**
     * 显示名称
     * <p>
     * 首次自动注册时随机生成（如"神秘的猫咪"），可修改，不要求唯一。
     * 所有房间共用同一昵称，改名后通过事件通知各房间刷新。
     */
    private String nickname;

    /**
     * 头像背景色（如 #FF6B6B）
     * <p>
     * 前端展示：纯色圆形背景 + 昵称首字
     */
    private String avatarColor;

    /**
     * 头像 URL
     * <p>
     * 注册用户可上传自定义头像，匿名用户默认为空串（使用 avatarColor 方案）
     */
    private String avatarUrl;

    /**
     * 密码（BCrypt 加密）
     * <p>
     * 空字符串 = 未设置密码。
     * 匿名用户可选设置密码（用于跨设备登录），注册用户必须设置密码。
     */
    private String password;

    // ==================== 注册信息（匿名用户全部为 NULL） ====================

    /**
     * 邮箱
     * <p>
     * 注册时绑定，用于注册用户的登录方式。NULL = 未注册
     */
    private String email;

    /**
     * 自己的邀请码
     * <p>
     * 注册时由系统生成，其他用户可用此码注册。NULL = 未注册
     */
    private String inviteCode;

    /**
     * 邀请人账号 ID（t_account.id）
     * <p>
     * 记录是谁邀请该用户注册的，用于关系链追踪和积分奖励。NULL = 无邀请人
     */
    private Long invitedBy;

    /**
     * 积分余额
     * <p>
     * 邀请奖励、活跃奖励等
     */
    private Integer credits;

    // ==================== 状态 ====================

    /**
     * 是否已注册
     * <p>
     * 0 = 匿名成员（只有 account_id + 昵称）
     * 1 = 已注册用户（有 email + 密码，可创建房间、管理成员）
     */
    private Integer isRegistered;

    /**
     * 账号状态
     * <p>
     * 0 = 封禁，1 = 正常
     */
    private Integer status;

    /**
     * 最近活跃时间
     */
    private LocalDateTime lastActiveTime;

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
     * 逻辑删除
     */
    @TableLogic
    private Integer delFlag;

    // ==================== 便捷方法 ====================

    /**
     * 是否为已注册用户
     */
    public boolean registered() {
        return isRegistered != null && isRegistered == 1;
    }

    /**
     * 是否已设置密码
     */
    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    /**
     * 是否正常状态（未封禁）
     */
    public boolean isNormal() {
        return status != null && status == 1;
    }
}