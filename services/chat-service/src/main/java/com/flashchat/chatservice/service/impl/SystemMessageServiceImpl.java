package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.config.MsgIdGenerator;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.service.MessageSideEffectService;
import com.flashchat.chatservice.service.SystemMessageService;
import com.flashchat.chatservice.service.dispatch.RoomSerialLock;
import com.flashchat.chatservice.service.persist.PersistResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
        // 锁外预计算:msgId/时间戳等与 msgSeqId 无关,提前算好降低临界区体积。
        // createTime 统一用一个时刻源,保证 DB/Stream/广播时间一致。
        String msgId = UUID.randomUUID().toString().replace("-", "");
        long nowMillis = System.currentTimeMillis();
        LocalDateTime createTime = LocalDateTime.now();
        PersistResult persistResult;
        // 房间级串行临界区:与 ChatServiceImpl.sendMsg 共享同一把锁,
        // 保证系统消息与用户消息在同一房间内的 msgSeqId 分配顺序
        // 与 mailbox 提交顺序严格一致。
        // 系统消息是后台触发(入房通知/房主变更等),没有用户直接等待,
        // 超时时刻意降级为"打一条 WARN 丢弃",不向上抛阻断主业务。
        RoomSerialLock.Handle handle;
        try {
            handle = roomSerialLock.acquire(roomId);
        } catch (RoomSerialLock.StripeLockTimeoutException e) {
            log.warn("[系统消息-stripe超时丢弃] room={}, cause={}", roomId, e.getMessage());
            return;
        } catch (RoomSerialLock.StripeLockInterruptedException e) {
            log.warn("[系统消息-线程中断丢弃] room={}, cause={}", roomId, e.getMessage());
            return;
        }
        try {
            // 1. 生成消息 seqId
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
                    .createTime(createTime)
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
                    .timestamp(nowMillis)
                    .isHost(false)
                    .msgType(2)
                    .system(true)
                    .deleted(false)
                    .build();
            // 5. 提交 side effect mailbox(写窗口 + 广播)
            messageSideEffectService.dispatchSystemMessageAccepted(roomId, msgSeqId, broadcastMsg);
        } finally {
            handle.close();
        }

        log.info("[系统消息] room={}, acceptedBy={}, content={}",
                roomId, persistResult.acceptedBy(), content);
    }
}
