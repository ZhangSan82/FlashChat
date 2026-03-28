package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 房间延期 WS 广播数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomExtendRespDTO {

    /** 新的到期时间 */
    private LocalDateTime newExpireTime;

    /** 延期时长描述 */
    private String durationDesc;

    /** 房间当前状态（延期后回退到 ACTIVE） */
    private Integer status;

    /** 状态描述 */
    private String statusDesc;
}
