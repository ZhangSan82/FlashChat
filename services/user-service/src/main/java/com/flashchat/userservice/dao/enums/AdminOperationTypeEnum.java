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
    CREDIT_ADJUST_INCREASE("手动增加积分"),
    CREDIT_ADJUST_DECREASE("手动扣减积分"),
    ROOM_CLOSE("房间强制关闭"),
    ROOM_MEMBER_KICK("房间踢人"),
    ROOM_MEMBER_MUTE("房间禁言"),
    ROOM_MEMBER_UNMUTE("房间解除禁言");

    private final String desc;
}
