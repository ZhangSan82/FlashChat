package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberInfoRespDTO {

    /** 面向用户的账号ID*/
    private String accountId;

    /** 昵称 */
    private String nickname;

    /** 头像背景色 */
    private String avatarColor;
}