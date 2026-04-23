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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
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
 *
 * <p>补充说明：第 4 步的历史注释保留不变，但当前 unread 已改成“读时 DB 统计”，
 * 所以发送副作用阶段不再真正执行未读 fan-out。
 */
@Slf4j
@Service
public class MessageSideEffectServiceImpl implements MessageSideEffectService {

    private static final String METRIC_FAIL = "chat.side_effect.fail";
    private static final String METRIC_MAILBOX_REJECTED = "chat.side_effect.mailbox.rejected";
    private static final String METRIC_STEP_DURATION = "chat.side_effect.step.duration";

    private final RoomSideEffectMailbox roomSideEffectMailbox;
    private final MessageWindowService messageWindowService;
    private final RoomChannelManager roomChannelManager;
    private final UnreadService unreadService;
    private final MeterRegistry meterRegistry;

    private final Counter failWindowAddCounter;
    private final Counter failWindowUpdateCounter;
    private final Counter failWindowRemoveCounter;
    private final Counter failBroadcastCounter;
    private final Counter failBroadcastReactionCounter;
    private final Counter failBroadcastStateCounter;
    private final Counter failTouchCounter;
    private final Counter failUnreadCounter;
    private final Counter mailboxRejectedCounter;
    private final Timer windowAddTimer;
    private final Timer windowUpdateTimer;
    private final Timer windowRemoveTimer;
    private final Timer broadcastTimer;
    private final Timer broadcastReactionTimer;
    private final Timer broadcastStateTimer;
    private final Timer touchTimer;
    private final Timer unreadTimer;

    public MessageSideEffectServiceImpl(RoomSideEffectMailbox roomSideEffectMailbox,
                                        MessageWindowService messageWindowService,
                                        RoomChannelManager roomChannelManager,
                                        UnreadService unreadService,
                                        MeterRegistry meterRegistry) {
        this.roomSideEffectMailbox = roomSideEffectMailbox;
        this.messageWindowService = messageWindowService;
        this.roomChannelManager = roomChannelManager;
        this.unreadService = unreadService;
        this.meterRegistry = meterRegistry;
        this.failWindowAddCounter = buildFailCounter("window_add");
        this.failWindowUpdateCounter = buildFailCounter("window_update");
        this.failWindowRemoveCounter = buildFailCounter("window_remove");
        this.failBroadcastCounter = buildFailCounter("broadcast");
        this.failBroadcastReactionCounter = buildFailCounter("broadcast_reaction");
        this.failBroadcastStateCounter = buildFailCounter("broadcast_state");
        this.failTouchCounter = buildFailCounter("touch");
        this.failUnreadCounter = buildFailCounter("unread");
        this.mailboxRejectedCounter = meterRegistry == null ? null
                : Counter.builder(METRIC_MAILBOX_REJECTED)
                .description("副作用任务入 mailbox 被拒绝的次数")
                .register(meterRegistry);
        this.windowAddTimer = buildStepTimer("window_add");
        this.windowUpdateTimer = buildStepTimer("window_update");
        this.windowRemoveTimer = buildStepTimer("window_remove");
        this.broadcastTimer = buildStepTimer("broadcast");
        this.broadcastReactionTimer = buildStepTimer("broadcast_reaction");
        this.broadcastStateTimer = buildStepTimer("broadcast_state");
        this.touchTimer = buildStepTimer("touch");
        this.unreadTimer = buildStepTimer("unread");
    }

    private Counter buildFailCounter(String type) {
        if (meterRegistry == null) {
            return null;
        }
        return Counter.builder(METRIC_FAIL)
                .tags(Tags.of("type", type))
                .description("房间副作用单项执行失败次数")
                .register(meterRegistry);
    }

    private static void safeIncrement(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private Timer buildStepTimer(String step) {
        if (meterRegistry == null) {
            return null;
        }
        return Timer.builder(METRIC_STEP_DURATION)
                .tags(Tags.of("step", step))
                .description("Time spent in a single message side-effect step")
                .register(meterRegistry);
    }

    private static void recordStep(Timer timer, Runnable action) {
        if (timer != null) {
            timer.record(action);
            return;
        }
        action.run();
    }

    @Override
    public void dispatchUserMessageAccepted(String roomId,
                                            Long senderId,
                                            Long msgSeqId,
                                            ChatBroadcastMsgRespDTO broadcastMsg) {
        // 注意: 用户消息的 window_add 已在发送主链路中通过 XADD + EVAL pipeline 合并执行
        // (见 MessagePersistServiceImpl#saveAsyncWithWindow), mailbox 不再重复写窗口,
        // 避免一条消息被 ZADD 两次。系统消息仍沿用 safeAddToWindow(见下面 dispatchSystemMessageAccepted)。
        roomSideEffectMailbox.submit(roomId, "user-msg-" + msgSeqId, () -> {
            safeBroadcastChat(roomId, broadcastMsg);
            safeTouchMember(roomId, senderId);
            /*
             * 旧逻辑：用户消息被接受后，会在 mailbox 副作用阶段执行 safeIncrementUnread(roomId, senderId)。
             *
             * 之所以保留这段注释而不是直接删除，是为了明确说明本次改造的边界：
             *   1. unread 不再通过“发消息后对房间内其他成员逐个 +1”的方式维护；
             *   2. unread 改为在查询时基于 last_ack_msg_id 从 DB 实时统计；
             *   3. 因此这里不再把 unread 作为发送链路上的副作用步骤。
             *
             * safeIncrementUnread(roomId, senderId);
             */
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                safeIncrement(mailboxRejectedCounter);
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
                safeIncrement(mailboxRejectedCounter);
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
                safeIncrement(mailboxRejectedCounter);
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
                safeIncrement(mailboxRejectedCounter);
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
                safeIncrement(mailboxRejectedCounter);
                log.error("[删除副作用下沉失败] room={}, msgSeqId={}",
                        roomId, msgSeqId, throwable);
            }
        });
    }

    private void safeAddToWindow(String roomId, Long msgSeqId, ChatBroadcastMsgRespDTO broadcastMsg) {
        try {
            recordStep(windowAddTimer, () -> messageWindowService.addToWindow(roomId, msgSeqId, broadcastMsg));
        } catch (Exception e) {
            safeIncrement(failWindowAddCounter);
            log.error("[副作用: 窗口写入失败] room={}, msgSeqId={}", roomId, msgSeqId, e);
        }
    }

    private void safeUpdateWindow(String roomId, Long msgSeqId, ChatBroadcastMsgRespDTO windowMsg) {
        if (windowMsg == null) {
            return;
        }
        try {
            recordStep(windowUpdateTimer,
                    () -> messageWindowService.updateMemberByScore(roomId, msgSeqId, JsonUtil.toJson(windowMsg)));
        } catch (Exception e) {
            safeIncrement(failWindowUpdateCounter);
            log.error("[副作用: 窗口更新失败] room={}, msgSeqId={}", roomId, msgSeqId, e);
        }
    }

    private void safeRemoveFromWindow(String roomId, Long msgSeqId) {
        try {
            recordStep(windowRemoveTimer, () -> messageWindowService.removeMemberByScore(roomId, msgSeqId));
        } catch (Exception e) {
            safeIncrement(failWindowRemoveCounter);
            log.error("[副作用: 窗口删除失败] room={}, msgSeqId={}", roomId, msgSeqId, e);
        }
    }

    private void safeBroadcastChat(String roomId, ChatBroadcastMsgRespDTO broadcastMsg) {
        try {
            recordStep(broadcastTimer, () -> roomChannelManager.broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg)));
        } catch (Exception e) {
            safeIncrement(failBroadcastCounter);
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
            recordStep(broadcastReactionTimer, () -> roomChannelManager.broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.MSG_REACTION_UPDATE, respDTO)));
        } catch (Exception e) {
            safeIncrement(failBroadcastReactionCounter);
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
            recordStep(broadcastStateTimer, () -> roomChannelManager.broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, eventType, broadcastData)));
        } catch (Exception e) {
            safeIncrement(failBroadcastStateCounter);
            log.error("[副作用: 消息状态广播失败] room={}, msgSeqId={}, eventType={}",
                    roomId, msgSeqId, eventType, e);
        }
    }

    private void safeTouchMember(String roomId, Long senderId) {
        try {
            recordStep(touchTimer, () -> roomChannelManager.touchMember(roomId, senderId));
        } catch (Exception e) {
            safeIncrement(failTouchCounter);
            log.error("[副作用: 活跃时间更新失败] room={}, senderId={}", roomId, senderId, e);
        }
    }

    private void safeIncrementUnread(String roomId, Long senderId) {
        /*
         * 旧逻辑：在 mailbox 的单线程 shard 内执行 unreadService.incrementUnread(roomId, senderId)。
         *
         * 该逻辑已经被停用，但方法本身保留，原因如下：
         *   1. 便于从日志和调用链上看出 unread 曾经属于 side-effect 的一部分；
         *   2. 方便后续做灰度对比时快速恢复旧实现；
         *   3. 结合 probe 指标，可以更清楚地区分“历史瓶颈位置”和“当前有效路径”。
         *
         * 旧实现：
         * try {
         *     recordStep(unreadTimer, () -> unreadService.incrementUnread(roomId, senderId));
         * } catch (Exception e) {
         *     safeIncrement(failUnreadCounter);
         *     log.error("[副作用: 未读数更新失败] room={}, senderId={}", roomId, senderId, e);
         * }
         */

        // 新逻辑：unread 已经改为读时 DB 统计，因此发送副作用阶段不再执行任何 unread 更新。
        log.debug("[副作用: 未读数写时更新已停用] room={}, senderId={}", roomId, senderId);
    }
}
