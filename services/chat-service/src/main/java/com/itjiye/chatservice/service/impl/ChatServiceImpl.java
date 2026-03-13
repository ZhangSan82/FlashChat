package com.itjiye.chatservice.service.impl;

import com.itjiye.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.itjiye.chatservice.dto.req.ChatBroadcastMsgReqDTO;
import com.itjiye.chatservice.dto.req.SendMsgReqDTO;
import com.itjiye.chatservice.dto.resp.WsRespDTO;
import com.itjiye.chatservice.service.ChatService;
import com.itjiye.chatservice.toolkit.ChannelAttrUtil;
import com.itjiye.chatservice.websocket.manager.RoomChannelManager;
import com.itjiye.convention.exception.ClientException;
import io.netty.channel.Channel;
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

        // ===== 1. 找到发送者的 Channel =====
        // 发送者必须有 WebSocket 连接在这个房间里
        Channel senderChannel = roomChannelManager.findByMemberId(request.getRoomId(), request.getMemberId());
        if (senderChannel == null) {
            throw new ClientException("你不在该房间中，请先加入房间");
        }

        // ===== 2. 检查禁言 =====
        if (ChannelAttrUtil.isMuted(senderChannel)) {
            throw new ClientException("你已被禁言，无法发送消息");
        }

        // ===== 3. TODO: 敏感词过滤（下一步添加）=====

        // ===== 4. TODO: 保存到数据库 + @Transactional（下一步添加）=====

        // ===== 5. 构建广播消息 =====
        String msgId = UUID.randomUUID().toString().replace("-", "");

        ChatBroadcastMsgReqDTO broadcastMsg = ChatBroadcastMsgReqDTO.builder()
                ._id(msgId)
                .content(request.getContent())
                .senderId(request.getMemberId().toString())
                .username(ChannelAttrUtil.getNickname(senderChannel))
                .avatar(ChannelAttrUtil.getAvatar(senderChannel))
                .timestamp(System.currentTimeMillis())
                .isHost(ChannelAttrUtil.isHost(senderChannel))
                .build();

        // ===== 6. 通过 WebSocket 广播给房间所有人 =====
        roomChannelManager.broadcast(request.getRoomId(),
                WsRespDTO.of(WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[发送消息] room={}, from={}, content={}",
                request.getRoomId(), request.getMemberId(), request.getContent());
    }
}
