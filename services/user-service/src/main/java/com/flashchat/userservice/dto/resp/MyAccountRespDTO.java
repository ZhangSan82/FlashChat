package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 当前登录用户完整信息响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyAccountRespDTO {

    /** 数据库主键 ID */
    private Long id;

    /** 面向用户的账号 ID（FC-XXXXXX） */
    private String accountId;

    /** 昵称 */
    private String nickname;

    /** 头像背景色（#RRGGBB） */
    private String avatarColor;

    /** 头像 URL（空串表示未上传，使用 avatarColor 方案） */
    private String avatarUrl;

    /** 邮箱（脱敏显示，如 t***@gmail.com），未绑定为 null */
    private String email;

    /** 积分余额 */
    private Integer credits;

    /** 是否已注册 */
    private Boolean isRegistered;

    /** 是否已设置密码 */
    private Boolean hasPassword;

    /** 自己的邀请码（未注册为 null） */
    private String inviteCode;

    /** 注册时间 */
    private LocalDateTime createTime;
}