package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 账号信息响应（替代 MemberInfoRespDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountInfoRespDTO {

    /** 面向用户的账号 ID（FC-XXXXXX） */
    private String accountId;

    /** 昵称 */
    private String nickname;

    /** 头像背景色 */
    private String avatarColor;

    /** 头像 URL */
    private String avatarUrl;

    /** 是否已注册 */
    private Boolean isRegistered;
}