package com.flashchat.chatservice.service;


import java.util.Map;

/**
 * 未读消息计数服务
 * Redis Hash 存储：
 *   Key:   flashchat:unread:{memberId}
 *   Field: roomId
 *   Value: 未读消息数（字符串形式的整数）
 */
public interface UnreadService {

    /**
     * 发消息后，给房间所有成员（除发送者）的未读数 +1
     */
    void incrementUnread(String roomId, Long excludeMemberId);

    /**
     * ACK 后，清除该用户该房间的未读数
     */
    void clearUnread(Long memberId, String roomId);

    /**
     * 离开房间 / 被踢出
     */
    void removeRoomUnread(Long memberId, String roomId);

    /**
     * 房间关闭，批量清除所有成员的该房间未读数
     */
    void clearRoomForAllMembers(String roomId);

    /**
     * 获取用户所有房间的未读数
     * @return roomId → unreadCount（只包含 > 0 的）
     */
    Map<String, Integer> getAllUnreadCounts(Long memberId);

    /**
     * 获取用户在某个房间的未读数
     */
    int getUnreadCount(Long memberId, String roomId);
}