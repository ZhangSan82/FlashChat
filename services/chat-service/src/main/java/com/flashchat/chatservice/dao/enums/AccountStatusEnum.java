package com.flashchat.chatservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 账号状态枚举
 * 对应 t_account.status
 */
@Getter
@AllArgsConstructor
public enum AccountStatusEnum {

    BANNED(0, "封禁"),
    NORMAL(1, "正常");

    private final int code;
    private final String desc;
}