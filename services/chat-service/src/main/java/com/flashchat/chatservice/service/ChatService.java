package com.flashchat.chatservice.service;


import com.flashchat.chatservice.dto.req.SendMsgReqDTO;

public interface ChatService{
    /**
     * 发送消息
     */
    void sendMsg(SendMsgReqDTO request);
}
