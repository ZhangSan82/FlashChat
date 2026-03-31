package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.config.MsgIdGenerator;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.MessageWindowService;
import com.flashchat.chatservice.service.SystemMessageService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 系统消息服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMessageServiceImpl implements SystemMessageService {

    private final MsgIdGenerator msgIdGenerator;
    private final MessagePersistServiceImpl messagePersistService;
    private final MessageWindowService messageWindowService;
    private final RoomChannelManager roomChannelManager;

    @Override
    public void sendToRoom(String roomId, String content) {
        try {
            // 1. 生成消息 ID
            String msgId = UUID.randomUUID().toString().replace("-", "");
            Long msgSeqId = msgIdGenerator.tryNextId();
            if (msgSeqId == null) {
                msgSeqId = msgIdGenerator.fallbackNextId();
            }
            // 2. 构建消息实体
            MessageDO messageDO = MessageDO.builder()
                    .msgId(msgId)
                    .roomId(roomId)
                    .content(content)
                    .body(null)
                    .msgType(2)        // 系统消息
                    .nickname("系统")
                    .senderId(0L)      // 系统发送者
                    .avatarColor("")
                    .status(0)
                    .isHost(0)
                    .build();
            messageDO.setId(msgSeqId);
            // 3. 异步持久化
            messagePersistService.saveAsync(messageDO);
            // 4. 构建广播 DTO
            ChatBroadcastMsgRespDTO broadcastMsg = ChatBroadcastMsgRespDTO.builder()
                    ._id(msgId)
                    .indexId(msgSeqId)
                    .content(content)
                    .senderId("0")
                    .username("系统")
                    .avatar("")
                    .timestamp(System.currentTimeMillis())
                    .isHost(false)
                    .msgType(2)
                    .system(true)
                    .deleted(false)
                    .build();
            // 5. 写入消息滑动窗口
            messageWindowService.addToWindow(roomId, msgSeqId, broadcastMsg);
            // 6. 广播给聊天房间全员
            roomChannelManager.broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));
            log.info("[系统消息] room={}, content={}", roomId, content);
        } catch (Exception e) {
            log.error("[系统消息发送失败] room={}, content={}", roomId, content, e);
        }
    }
}