package com.flashchat.chatservice.websocket.manager;

import lombok.Builder;
import lombok.Data;

/**
 * 用户在某个房间中的身份信息
 *
 * 旧方案：这些信息存在 Channel 属性上，一个 Channel 只能在一个房间
 * 新方案：存在 RoomChannelManager 的内存结构中，同一个用户在不同房间可以有不同状态
 *
 * 举例：
 *   用户 Alice 同时在 room_1 和 room_2 中
 *   - room_1 中她是房主（isHost=true），没被禁言
 *   - room_2 中她是普通成员（isHost=false），被禁言了
 *   这两份状态由两个独立的 RoomMemberInfo 对象管理
 */
@Data
@Builder
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
}