package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 当前登录用户完整信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyAccountRespDTO {

    private Long id;
    private String accountId;
    private String nickname;
    private String avatarColor;
    private String avatarUrl;
    private String email;
    private Integer credits;
    private Boolean isRegistered;
    private Boolean hasPassword;
    private String inviteCode;

    /** 系统角色。0=普通用户，1=管理员。 */
    private Integer systemRole;

    /** 当前账号是否是管理员。 */
    private Boolean isAdmin;

    private LocalDateTime createTime;
}
