package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 管理员操作类型。
 */
@Getter
@AllArgsConstructor
public enum AdminOperationTypeEnum {

    ACCOUNT_BAN("账号封禁"),
    ACCOUNT_UNBAN("账号解封"),
    ACCOUNT_KICKOUT("账号强制下线"),
    ACCOUNT_GRANT_ADMIN("授予管理员"),
    ACCOUNT_REVOKE_ADMIN("撤销管理员"),
    CREDIT_ADJUST_INCREASE("手动增加积分"),
    CREDIT_ADJUST_DECREASE("手动扣减积分"),
    ROOM_CLOSE("房间强制关闭"),
    ROOM_MEMBER_KICK("房间踢人"),
    ROOM_MEMBER_MUTE("房间禁言"),
    ROOM_MEMBER_UNMUTE("房间解除禁言"),
    FEEDBACK_PROCESS("用户反馈处理");

    private final String desc;

    public static AdminOperationTypeEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (AdminOperationTypeEnum value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
