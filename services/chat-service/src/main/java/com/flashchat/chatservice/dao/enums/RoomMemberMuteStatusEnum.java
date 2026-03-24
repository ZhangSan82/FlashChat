package com.flashchat.chatservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RoomMemberMuteStatusEnum {


    UNMUTE(0, "未禁言"),
    MUTE(1, "禁言");

    private final int code;
    private final String desc;
}
