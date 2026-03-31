package com.flashchat.channel;

import java.util.Collection;

/**
 * 通道推送服务接口
 * <p>
 * 定义 WebSocket 消息推送能力，由 chat-service 的 RoomChannelManager 实现。
 */
public interface ChannelPushService {

    /**
     * 单推给某个用户
     * <p>
     * 用户不在线时静默忽略，不抛异常。
     * @param accountId 目标用户 ID
     * @param type      消息类型编号
     * @param roomId    关联房间 ID
     * @param data      消息体，由实现方 JSON 序列化
     */
    void sendToUser(Long accountId, int type, String roomId, Object data);

    /**
     * 批量推送给多个用户
     * <p>
     * 将消息推送给集合中所有在线用户，跳过不在线和无法推送的 ID
     * @param accountIds 目标用户 ID 集合
     * @param type       消息类型编号
     * @param roomId     关联房间 ID
     * @param data       消息体
     */
    void sendToUsers(Collection<Long> accountIds, int type, String roomId, Object data);

    /**
     * 广播给聊天房间全部在线成员
     * <p>
     * 推给所有在该房间有活跃 WebSocket 连接的用户。
     * @param roomId 聊天房间 ID（t_room.room_id）
     * @param type   消息类型编号
     * @param data   消息体
     */
    void broadcastToRoom(String roomId, int type, Object data);
}