package com.flashchat.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端账号详情与列表项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAccountRespDTO {

    private Long id;
    private String accountId;
    private String nickname;
    private String avatarColor;
    private String avatarUrl;
    private String email;
    private Integer credits;
    private Boolean isRegistered;
    private Integer status;
    private String statusDesc;
    private Integer systemRole;
    private String systemRoleDesc;
    private LocalDateTime lastActiveTime;
    private LocalDateTime createTime;
}
