package com.flashchat.chatservice.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.chatservice.dao.entity.MemberDO;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;

import com.flashchat.chatservice.dto.req.SendMsgReqDTO;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.ChatService;
import com.flashchat.chatservice.service.MemberService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl extends ServiceImpl<MessageMapper,MessageDO> implements ChatService {

    private final RoomChannelManager roomChannelManager;
    private final MemberService  memberService;

    @Override
    public ChatBroadcastMsgRespDTO sendMsg(SendMsgReqDTO request) {


        MemberDO memberDO = memberService.getByAccountId(request.getAccountId());
        Long memberId = memberDO.getId();

        String roomId = request.getRoomId();


        // ===== 1. 检查用户是否在房间中 TODO应该用数据库查=====
        if (!roomChannelManager.isInRoom(roomId, memberId)) {
            throw new ClientException("你不在该房间中，请先加入房间");
        }

        // ===== 2. 获取成员信息 + 检查禁言 =====
        RoomMemberInfo memberInfo = roomChannelManager.getRoomMemberInfo(roomId, memberId);
        if (memberInfo != null && memberInfo.isMuted()) {
            throw new ClientException("你已被禁言，无法发送消息");
        }

        // ===== 3. TODO: 敏感词过滤（下一步添加）=====


        // ===== 4. 构建广播消息 =====
        String msgId = UUID.randomUUID().toString().replace("-", "");

        ChatBroadcastMsgRespDTO broadcastMsg = ChatBroadcastMsgRespDTO.builder()
                ._id(msgId)
                .content(request.getContent())
                .senderId(memberId.toString())
                .username(memberInfo != null ? memberInfo.getNickname() : "匿名")
                .avatar(memberInfo != null ? memberInfo.getAvatar() : "")
                .timestamp(System.currentTimeMillis())
                .isHost(memberInfo != null && memberInfo.isHost())
                .build();

        // ===== 5. 通过 WebSocket 广播给房间所有人 =====
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[发消息] room={}, memberId={}, content={}",
                roomId, memberId, request.getContent());


        // ===== 6. TODO: 保存到数据库  ,异步添加=====
        // messageBatchWriter.addMessage(message);
        Integer isHost = memberInfo != null && memberInfo.isHost() ? 1 : 0;
        MessageDO messageDO = MessageDO.builder()
                .msgId(msgId)
                .roomId(roomId)
                .content(request.getContent())
                .avatarColor(memberInfo.getAvatar())
                .msgType(1)//TODO
                .nickname(memberInfo.getNickname())
                .senderMemberId(memberId)
                .status(0)//TODO
                .isHost(isHost)
                .createTime(LocalDateTime.now())
                .build();
        this.save(messageDO);


        return broadcastMsg;
    }
}