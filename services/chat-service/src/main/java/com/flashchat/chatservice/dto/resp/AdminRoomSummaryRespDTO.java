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
public class AdminRoomSummaryRespDTO {

    private String roomId;

    private String title;

    private Integer status;

    private String statusDesc;

    private Integer isPublic;

    private String visibilityDesc;

    private Integer memberCount;

    private Integer maxMembers;

    private Integer onlineCount;

    private LocalDateTime createTime;

    private LocalDateTime expireTime;
}
