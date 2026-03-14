package com.flashchat.chatservice.service.impl;


import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.req.ChatBroadcastMsgReqDTO;
import com.flashchat.chatservice.dto.req.SendMsgReqDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.ChatService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final RoomChannelManager roomChannelManager;

    @Override
    public void sendMsg(SendMsgReqDTO request) {

        String roomId = request.getRoomId();
        Long userId = request.getUserId();

        // ===== 1. 检查用户是否在房间中 =====
        if (!roomChannelManager.isInRoom(roomId, userId)) {
            throw new ClientException("你不在该房间中，请先加入房间");
        }

        // ===== 2. 获取成员信息 + 检查禁言 =====
        RoomMemberInfo memberInfo = roomChannelManager.getRoomMemberInfo(roomId, userId);
        if (memberInfo != null && memberInfo.isMuted()) {
            throw new ClientException("你已被禁言，无法发送消息");
        }

        // ===== 3. TODO: 敏感词过滤（下一步添加）=====

        // ===== 4. TODO: 保存到数据库 + @Transactional（下一步添加）=====
        MessageDO message = new MessageDO();



        // ===== 5. 构建广播消息 =====
        String msgId = UUID.randomUUID().toString().replace("-", "");

        ChatBroadcastMsgReqDTO broadcastMsg = ChatBroadcastMsgReqDTO.builder()
                ._id(msgId)
                .content(request.getContent())
                .senderId(userId.toString())
                .username(memberInfo != null ? memberInfo.getNickname() : "匿名")
                .avatar(memberInfo != null ? memberInfo.getAvatar() : "")
                .timestamp(System.currentTimeMillis())
                .isHost(memberInfo != null && memberInfo.isHost())
                .build();

        // ===== 6. 通过 WebSocket 广播给房间所有人 =====
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[HTTP-发消息] room={}, userId={}, content={}",
                roomId, userId, request.getContent());
    }
}