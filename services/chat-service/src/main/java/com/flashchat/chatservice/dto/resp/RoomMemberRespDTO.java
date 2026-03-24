package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 房间成员的信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMemberRespDTO {

    /** 账号 ID（t_account.id） */
    private Long accountId;

    /** 昵称（优先从内存取最新值，fallback 到 DB） */
    private String nickname;

    /** 头像（颜色值如 #FF6B6B，或注册用户的头像 URL） */
    private String avatar;

    /** 角色：0-普通成员 1-房主 */
    private Integer role;

    /** 是否房主（前端直接用，不需要再判断 role） */
    private Boolean isHost;

    /** 是否被禁言 */
    private Boolean isMuted;

    /** 是否在线（有活跃的 WS 连接） */
    private Boolean isOnline;
}