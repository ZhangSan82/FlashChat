package com.itjiye.chatservice.dto.req;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserLeaveMsgReqDTO {
    private String nickname;
    private String reason;
    private Integer onlineCount;
}