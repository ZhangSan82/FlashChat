package com.flashchat.chatservice.service;


import com.flashchat.chatservice.dto.req.SendMsgReqDTO;

public interface ChatService{
    /**
     * 发送消息
     *
     * @param roomId   房间ID
     * @param memberId 发送者ID
     * @param content  消息内容
     */
    void sendMsg(SendMsgReqDTO request);
}
