package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 邀请码信息响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteCodeRespDTO {

    /** 邀请码 */
    private String code;

    /** 是否已被使用 */
    private Boolean used;

    /** 使用者的账号 ID（已使用时有值） */
    private String usedByAccountId;

    /** 创建时间 */
    private LocalDateTime createTime;
}