package com.flashchat.chatservice.service.impl;


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
        // 【对比旧代码】
        //   旧: findByMemberId(roomId, memberId) → 找 Channel，null 就说明不在
        //   新: isInRoom(roomId, userId) → 直接查 roomMembers 映射
        //       不需要找到 Channel，因为推送消息时 broadcastToRoom 会自动找
        if (!roomChannelManager.isInRoom(roomId, userId)) {
            throw new ClientException("你不在该房间中，请先加入房间");
        }

        // ===== 2. 获取成员信息 + 检查禁言 =====
        // 【对比旧代码】
        //   旧: ChannelAttrUtil.isMuted(senderChannel) → Channel 属性，全局一个禁言状态
        //   新: memberInfo.isMuted() → RoomMemberInfo，每个房间独立的禁言状态
        //       比如用户在 room_1 被禁言但在 room_2 没有，旧代码做不到这个区分
        RoomMemberInfo memberInfo = roomChannelManager.getRoomMemberInfo(roomId, userId);
        if (memberInfo != null && memberInfo.isMuted()) {
            throw new ClientException("你已被禁言，无法发送消息");
        }

        // ===== 3. TODO: 敏感词过滤（下一步添加）=====

        // ===== 4. TODO: 保存到数据库 + @Transactional（下一步添加）=====

        // ===== 5. 构建广播消息 =====
        // 【对比旧代码】
        //   旧: 从 ChannelAttrUtil 取昵称/头像/isHost → Channel 属性
        //   新: 从 memberInfo 取 → RoomMemberInfo 对象
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
        // 【对比旧代码】
        //   旧: roomChannelManager.broadcast(roomId, WsRespDTO.of(type, data))
        //       broadcast 内部用 ChannelGroup.writeAndFlush 一行搞定
        //   新: roomChannelManager.broadcastToRoom(roomId, WsRespDTO.of(roomId, type, data))
        //       broadcastToRoom 内部遍历成员 → 找 Channel → 逐个推送
        //       WsRespDTO.of 多了 roomId 参数，放在外层让前端区分是哪个房间的消息
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[HTTP-发消息] room={}, userId={}, content={}",
                roomId, userId, request.getContent());
    }
}