package com.flashchat.chatservice.dto.req;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserJoinMsgReqDTO {
    private String nickname;
    private String avatar;
    private Boolean isHost;
    private Integer onlineCount;
}