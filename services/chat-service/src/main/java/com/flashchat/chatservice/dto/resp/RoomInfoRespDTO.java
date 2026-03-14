package com.flashchat.chatservice.dto.resp;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RoomInfoRespDTO {

    private String roomId;
    private String title;
    private Integer status;
    private String statusDesc;
    private Integer maxMembers;
    private Integer isPublic;
    private Integer onlineCount;
    private Integer memberCount;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
}
