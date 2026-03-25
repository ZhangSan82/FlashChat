package com.flashchat.chatservice.websocket.manager;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户在某个房间中的身份信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMemberInfo {

    /** 用户ID */
    private Long userId;

    /** 在该房间使用的昵称 */
    private String nickname;

    /** 在该房间使用的头像 */
    private String avatar;

    /** 是否为该房间的房主 */
    private boolean isHost;

    /** 是否在该房间被禁言 */
    private volatile boolean isMuted;

    /**
     * 最后活跃时间（毫秒时间戳）
     * 更新时机：joinRoom、online 重连、发消息时 touchMember
     * 用途：RoomMemberCleanupJob 判断是否为僵尸成员
     */
    private volatile long lastActiveTime;
}