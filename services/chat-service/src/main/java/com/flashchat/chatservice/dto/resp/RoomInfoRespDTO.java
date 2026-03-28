package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomInfoRespDTO {

    private String roomId;
    private String title;
    private Integer status;
    private String statusDesc;
    private Integer maxMembers;
    private Integer unreadCount;
    /**
     * 房间分享链接
     */
    private String shareUrl;
    private Integer isPublic;
    private Integer onlineCount;
    private Integer memberCount;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
}
