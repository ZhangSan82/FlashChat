package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.MsgReactionRespDTO;
import com.flashchat.chatservice.dto.resp.MsgRecallRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.MessageSideEffectService;
import com.flashchat.chatservice.service.MessageWindowService;
import com.flashchat.chatservice.service.UnreadService;
import com.flashchat.chatservice.service.dispatch.RoomSideEffectMailbox;
import com.flashchat.chatservice.toolkit.JsonUtil;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 消息发送成功后的副作用服务
 * 副作用统一进入 RoomMailbox，主链路不再同步执行：
 *   1. 滑动窗口写入
 *   2. WebSocket 广播
 *   3. 发送者活跃时间更新
 *   4. 未读数递增
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageSideEffectServiceImpl implements MessageSideEffectService {

    private final RoomSideEffectMailbox roomSideEffectMailbox;
    private final MessageWindowService messageWindowService;
    private final RoomChannelManager roomChannelManager;
    private final UnreadService unreadService;

    @Override
    public void dispatchUserMessageAccepted(String roomId,
                                            Long senderId,
                                            Long msgSeqId,
                                            ChatBroadcastMsgRespDTO broadcastMsg) {
        roomSideEffectMailbox.submit(roomId, "user-msg-" + msgSeqId, () -> {
            safeAddToWindow(roomId, msgSeqId, broadcastMsg);
            safeBroadcastChat(roomId, broadcastMsg);
            safeTouchMember(roomId, senderId);
            safeIncrementUnread(roomId, senderId);
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                log.error("[消息副作用下沉失败] room={}, msgSeqId={}, senderId={}",
                        roomId, msgSeqId, senderId, throwable);
            }
        });
    }

    @Override
    public void dispatchSystemMessageAccepted(String roomId,
                                              Long msgSeqId,
                                              ChatBroadcastMsgRespDTO broadcastMsg) {
        roomSideEffectMailbox.submit(roomId, "system-msg-" + msgSeqId, () -> {
            safeAddToWindow(roomId, msgSeqId, broadcastMsg);
            safeBroadcastChat(roomId, broadcastMsg);
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                log.error("[系统消息副作用下沉失败] room={}, msgSeqId={}",
                        roomId, msgSeqId, throwable);
            }
        });
    }

    @Override
    public void dispatchReactionUpdated(String roomId,
                                        Long msgSeqId,
                                        String msgId,
                                        Map<String, List<String>> reactions,
                                        ChatBroadcastMsgRespDTO windowMsg) {
        roomSideEffectMailbox.submit(roomId, "reaction-" + msgSeqId, () -> {
            safeUpdateWindow(roomId, msgSeqId, windowMsg);
            safeBroadcastReaction(roomId, msgId, msgSeqId, reactions);
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                log.error("[reaction 副作用下沉失败] room={}, msgSeqId={}",
                        roomId, msgSeqId, throwable);
            }
        });
    }

    @Override
    public void dispatchMessageRecalled(String roomId,
                                        Long msgSeqId,
                                        String msgId,
                                        String senderId,
                                        ChatBroadcastMsgRespDTO recalledMsg) {
        roomSideEffectMailbox.submit(roomId, "recall-" + msgSeqId, () -> {
            safeUpdateWindow(roomId, msgSeqId, recalledMsg);
            safeBroadcastMessageState(roomId,
                    WsRespDTOTypeEnum.MSG_RECALLED,
                    msgId,
                    msgSeqId,
                    senderId);
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                log.error("[撤回副作用下沉失败] room={}, msgSeqId={}",
                        roomId, msgSeqId, throwable);
            }
        });
    }

    @Override
    public void dispatchMessageDeleted(String roomId,
                                       Long msgSeqId,
                                       String msgId,
                                       String senderId) {
        roomSideEffectMailbox.submit(roomId, "delete-" + msgSeqId, () -> {
            safeRemoveFromWindow(roomId, msgSeqId);
            safeBroadcastMessageState(roomId,
                    WsRespDTOTypeEnum.MSG_DELETED,
                    msgId,
                    msgSeqId,
                    senderId);
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                log.error("[删除副作用下沉失败] room={}, msgSeqId={}",
                        roomId, msgSeqId, throwable);
            }
        });
    }

    private void safeAddToWindow(String roomId, Long msgSeqId, ChatBroadcastMsgRespDTO broadcastMsg) {
        try {
            messageWindowService.addToWindow(roomId, msgSeqId, broadcastMsg);
        } catch (Exception e) {
            log.error("[副作用: 窗口写入失败] room={}, msgSeqId={}", roomId, msgSeqId, e);
        }
    }

    private void safeUpdateWindow(String roomId, Long msgSeqId, ChatBroadcastMsgRespDTO windowMsg) {
        if (windowMsg == null) {
            return;
        }
        try {
            messageWindowService.updateMemberByScore(roomId, msgSeqId, JsonUtil.toJson(windowMsg));
        } catch (Exception e) {
            log.error("[副作用: 窗口更新失败] room={}, msgSeqId={}", roomId, msgSeqId, e);
        }
    }

    private void safeRemoveFromWindow(String roomId, Long msgSeqId) {
        try {
            messageWindowService.removeMemberByScore(roomId, msgSeqId);
        } catch (Exception e) {
            log.error("[副作用: 窗口删除失败] room={}, msgSeqId={}", roomId, msgSeqId, e);
        }
    }

    private void safeBroadcastChat(String roomId, ChatBroadcastMsgRespDTO broadcastMsg) {
        try {
            roomChannelManager.broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));
        } catch (Exception e) {
            log.error("[副作用: 广播失败] room={}, msgSeqId={}", roomId, broadcastMsg.getIndexId(), e);
        }
    }

    private void safeBroadcastReaction(String roomId,
                                       String msgId,
                                       Long msgSeqId,
                                       Map<String, List<String>> reactions) {
        try {
            MsgReactionRespDTO respDTO = MsgReactionRespDTO.builder()
                    .msgId(msgId)
                    .indexId(msgSeqId)
                    .reactions(reactions)
                    .build();
            roomChannelManager.broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.MSG_REACTION_UPDATE, respDTO));
        } catch (Exception e) {
            log.error("[副作用: reaction 广播失败] room={}, msgSeqId={}", roomId, msgSeqId, e);
        }
    }

    private void safeBroadcastMessageState(String roomId,
                                           WsRespDTOTypeEnum eventType,
                                           String msgId,
                                           Long msgSeqId,
                                           String senderId) {
        try {
            MsgRecallRespDTO broadcastData = MsgRecallRespDTO.builder()
                    .msgId(msgId)
                    .indexId(msgSeqId)
                    .senderId(senderId)
                    .build();
            roomChannelManager.broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, eventType, broadcastData));
        } catch (Exception e) {
            log.error("[副作用: 消息状态广播失败] room={}, msgSeqId={}, eventType={}",
                    roomId, msgSeqId, eventType, e);
        }
    }

    private void safeTouchMember(String roomId, Long senderId) {
        try {
            roomChannelManager.touchMember(roomId, senderId);
        } catch (Exception e) {
            log.error("[副作用: 活跃时间更新失败] room={}, senderId={}", roomId, senderId, e);
        }
    }

    private void safeIncrementUnread(String roomId, Long senderId) {
        try {
            unreadService.incrementUnread(roomId, senderId);
        } catch (Exception e) {
            log.error("[副作用: 未读数更新失败] room={}, senderId={}", roomId, senderId, e);
        }
    }
}
