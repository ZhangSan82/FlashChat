package com.flashchat.chatservice.service;

/**
 * 系统消息服务

 */
public interface SystemMessageService {

    /**
     * 向指定聊天房间发送一条系统消息
     */
    void sendToRoom(String roomId, String content);
}