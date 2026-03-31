package com.flashchat.channel;

/**
 * 通道查询服务接口
 * <p>
 * 提供 WebSocket 连接状态和聊天房间成员关系的查询能力。
 */
public interface ChannelQueryService {

    /**
     * 查询用户是否在线
     */
    boolean isOnline(Long accountId);

    /**
     * 查询用户是否是某个聊天房间的活跃成员
     */
    boolean isRoomMember(String roomId, Long accountId);

    /**
     * 获取聊天房间当前在线人数
     */
    int getRoomOnlineCount(String roomId);
}
