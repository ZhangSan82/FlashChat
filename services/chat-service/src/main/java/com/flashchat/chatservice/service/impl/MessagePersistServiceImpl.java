package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessagePersistServiceImpl {

    private final MessageMapper messageMapper;


    //TODO未来实现批量保存
    @Async("messageExecutor")
    public void saveAsync(MessageDO messageDO) {
        try {
            messageMapper.insert(messageDO);
        } catch (Exception e) {
            log.error("[消息持久化失败] msgId={}, roomId={}",
                    messageDO.getMsgId(), messageDO.getRoomId(), e);
        }
    }
}
