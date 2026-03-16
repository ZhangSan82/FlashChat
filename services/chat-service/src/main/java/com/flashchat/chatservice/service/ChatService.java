package com.flashchat.chatservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dto.req.SendMsgReqDTO;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import jakarta.validation.Valid;

public interface ChatService extends IService<MessageDO> {

    /**
     *发送消息
     */
    ChatBroadcastMsgRespDTO sendMsg(@Valid SendMsgReqDTO request);
}
