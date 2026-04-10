package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.config.MsgIdGenerator;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.service.MessageSideEffectService;
import com.flashchat.chatservice.service.SystemMessageService;
import com.flashchat.chatservice.service.dispatch.RoomSerialLock;
import com.flashchat.chatservice.service.persist.PersistResult;
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
    private final MessageSideEffectService messageSideEffectService;
    private final RoomSerialLock roomSerialLock;

    @Override
    public void sendToRoom(String roomId, String content) {
        PersistResult persistResult;
        // 房间级串行临界区:与 ChatServiceImpl.sendMsg 共享同一把锁,
        // 保证系统消息与用户消息在同一房间内的 msgSeqId 分配顺序
        // 与 mailbox 提交顺序严格一致。
        synchronized (roomSerialLock.lockFor(roomId)) {
            // 1. 生成消息 ID
            String msgId = UUID.randomUUID().toString().replace("-", "");
            Long msgSeqId = msgIdGenerator.tryNextId();
            boolean redisIdAllocated = msgSeqId != null;
            if (!redisIdAllocated) {
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
            // 3. 建立 durable handoff
            persistResult = redisIdAllocated
                    ? messagePersistService.saveAsync(messageDO)
                    : messagePersistService.saveSync(messageDO);
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
            // 5. 提交 side effect mailbox(写窗口 + 广播)
            messageSideEffectService.dispatchSystemMessageAccepted(roomId, msgSeqId, broadcastMsg);
        }

        log.info("[系统消息] room={}, acceptedBy={}, content={}",
                roomId, persistResult.acceptedBy(), content);
    }
}
