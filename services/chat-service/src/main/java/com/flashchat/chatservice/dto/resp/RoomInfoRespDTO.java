package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomInfoRespDTO {

    private String roomId;
    private String title;
    private String avatarUrl;
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
    private List<RoomPreviewMessageRespDTO> recentMessages;
}
