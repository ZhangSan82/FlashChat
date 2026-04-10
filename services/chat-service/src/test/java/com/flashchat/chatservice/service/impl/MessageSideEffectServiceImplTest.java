package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.service.MessageSideEffectService;
import com.flashchat.chatservice.service.MessageWindowService;
import com.flashchat.chatservice.service.UnreadService;
import com.flashchat.chatservice.service.dispatch.RoomSideEffectMailbox;
import com.flashchat.chatservice.toolkit.JsonUtil;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.MsgReactionRespDTO;
import com.flashchat.chatservice.dto.resp.MsgRecallRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageSideEffectServiceImplTest {

    @Mock
    private RoomSideEffectMailbox mailbox;

    @Mock
    private MessageWindowService messageWindowService;

    @Mock
    private RoomChannelManager roomChannelManager;

    @Mock
    private UnreadService unreadService;

    private MessageSideEffectService messageSideEffectService;

    @BeforeEach
    void setUp() {
        messageSideEffectService = new MessageSideEffectServiceImpl(
                mailbox,
                messageWindowService,
                roomChannelManager,
                unreadService
        );
    }

    /**
     * 作用：验证普通用户消息被系统可靠接住后，
     * side-effect service 会把“写窗口、广播、更新活跃时间、累加未读”四个副作用全部串起来执行。
     * 预期结果：这四类依赖都被调用一次，说明发送主链路已经只负责 durable accept，
     * 副作用则统一下沉到 mailbox 内执行。
     */
    @Test
    void dispatchUserMessageAcceptedShouldExecuteAllUserSideEffects() {
        when(mailbox.submit(eq("room-1"), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(2);
                    task.run();
                    return CompletableFuture.completedFuture(null);
                });

        ChatBroadcastMsgRespDTO broadcastMsg = buildBroadcastMsg();

        messageSideEffectService.dispatchUserMessageAccepted("room-1", 1L, 100L, broadcastMsg);

        verify(messageWindowService).addToWindow("room-1", 100L, broadcastMsg);
        verify(roomChannelManager).broadcastToRoom(eq("room-1"), any());
        verify(roomChannelManager).touchMember("room-1", 1L);
        verify(unreadService).incrementUnread("room-1", 1L);
    }

    /**
     * 作用：验证系统消息和普通用户消息共用 mailbox，
     * 但不会错误触发“发送者活跃时间”和“未读递增”这两个只属于用户消息的副作用。
     * 预期结果：系统消息仍会写窗口并广播，
     * 同时 touchMember() 和 incrementUnread() 必须完全不被调用。
     */
    @Test
    void dispatchSystemMessageAcceptedShouldSkipTouchAndUnread() {
        when(mailbox.submit(eq("room-1"), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(2);
                    task.run();
                    return CompletableFuture.completedFuture(null);
                });

        ChatBroadcastMsgRespDTO broadcastMsg = buildBroadcastMsg();

        messageSideEffectService.dispatchSystemMessageAccepted("room-1", 100L, broadcastMsg);

        verify(messageWindowService).addToWindow("room-1", 100L, broadcastMsg);
        verify(roomChannelManager).broadcastToRoom(eq("room-1"), any());
        verify(roomChannelManager, never()).touchMember(anyString(), any());
        verify(unreadService, never()).incrementUnread(anyString(), any());
    }

    /**
     * 作用：验证 reaction 更新也被纳入同一条房间副作用通道，
     * 不再由业务方法直接改窗口和广播。
     * 预期结果：窗口中的消息 JSON 会被更新为最新 reactions，
     * 且 WS 广播类型必须是 MSG_REACTION_UPDATE，消息 ID、索引 ID 和 reactions 内容都要正确。
     */
    @Test
    void dispatchReactionUpdatedShouldUpdateWindowAndBroadcastReactionEvent() {
        when(mailbox.submit(eq("room-1"), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(2);
                    task.run();
                    return CompletableFuture.completedFuture(null);
                });

        ChatBroadcastMsgRespDTO broadcastMsg = buildBroadcastMsg();
        Map<String, List<String>> reactions = Map.of("like", List.of("1"));

        messageSideEffectService.dispatchReactionUpdated(
                "room-1",
                100L,
                "msg-1",
                reactions,
                broadcastMsg
        );

        verify(messageWindowService).updateMemberByScore(
                "room-1",
                100L,
                JsonUtil.toJson(broadcastMsg)
        );
        ArgumentCaptor<WsRespDTO> wsCaptor = ArgumentCaptor.forClass(WsRespDTO.class);
        verify(roomChannelManager).broadcastToRoom(eq("room-1"), wsCaptor.capture());
        assertReactionBroadcast(wsCaptor.getValue(), "room-1", "msg-1", 100L, reactions);
    }

    /**
     * 作用：验证撤回消息后的副作用行为。
     * 预期结果：窗口中的原消息会被“撤回占位消息”替换，
     * 并且广播事件类型必须是 MSG_RECALLED，携带的 msgId、indexId、senderId 也必须准确。
     */
    @Test
    void dispatchMessageRecalledShouldReplaceWindowAndBroadcastRecallEvent() {
        when(mailbox.submit(eq("room-1"), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(2);
                    task.run();
                    return CompletableFuture.completedFuture(null);
                });

        ChatBroadcastMsgRespDTO recalledMsg = buildBroadcastMsg();

        messageSideEffectService.dispatchMessageRecalled(
                "room-1",
                100L,
                "msg-1",
                "1",
                recalledMsg
        );

        verify(messageWindowService).updateMemberByScore(
                "room-1",
                100L,
                JsonUtil.toJson(recalledMsg)
        );
        ArgumentCaptor<WsRespDTO> wsCaptor = ArgumentCaptor.forClass(WsRespDTO.class);
        verify(roomChannelManager).broadcastToRoom(eq("room-1"), wsCaptor.capture());
        assertRecallBroadcast(
                wsCaptor.getValue(),
                WsRespDTOTypeEnum.MSG_RECALLED,
                "room-1",
                "msg-1",
                100L,
                "1"
        );
    }

    /**
     * 作用：验证删除消息后的副作用行为。
     * 预期结果：窗口中的原消息会被直接移除而不是替换占位，
     * 并且广播事件类型必须是 MSG_DELETED，前端可据此把该消息从会话流里删掉。
     */
    @Test
    void dispatchMessageDeletedShouldRemoveWindowAndBroadcastDeleteEvent() {
        when(mailbox.submit(eq("room-1"), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(2);
                    task.run();
                    return CompletableFuture.completedFuture(null);
                });

        messageSideEffectService.dispatchMessageDeleted(
                "room-1",
                100L,
                "msg-1",
                "1"
        );

        verify(messageWindowService).removeMemberByScore("room-1", 100L);
        ArgumentCaptor<WsRespDTO> wsCaptor = ArgumentCaptor.forClass(WsRespDTO.class);
        verify(roomChannelManager).broadcastToRoom(eq("room-1"), wsCaptor.capture());
        assertRecallBroadcast(
                wsCaptor.getValue(),
                WsRespDTOTypeEnum.MSG_DELETED,
                "room-1",
                "msg-1",
                100L,
                "1"
        );
    }

    /**
     * 作用：统一构造一条最小聊天广播消息，减少各个副作用测试里的样板代码。
     * 预期结果：返回的 DTO 字段足以覆盖窗口写入和 WS 广播断言。
     */
    private ChatBroadcastMsgRespDTO buildBroadcastMsg() {
        return ChatBroadcastMsgRespDTO.builder()
                ._id("msg-1")
                .indexId(100L)
                .content("hello")
                .senderId("1")
                .username("tester")
                .avatar("#000000")
                .timestamp(System.currentTimeMillis())
                .isHost(false)
                .msgType(1)
                .deleted(false)
                .system(false)
                .build();
    }

    /**
     * 作用：集中校验 reaction 广播的事件类型和载荷内容。
     * 预期结果：广播类型必须是 MSG_REACTION_UPDATE，
     * 并且 data 中的 msgId、indexId、reactions 都与输入保持一致。
     */
    private void assertReactionBroadcast(WsRespDTO wsRespDTO,
                                         String roomId,
                                         String msgId,
                                         Long indexId,
                                         Map<String, List<String>> reactions) {
        assertEquals(WsRespDTOTypeEnum.MSG_REACTION_UPDATE.getType(), wsRespDTO.getType());
        assertEquals(roomId, wsRespDTO.getRoomId());
        MsgReactionRespDTO data = assertInstanceOf(MsgReactionRespDTO.class, wsRespDTO.getData());
        assertEquals(msgId, data.getMsgId());
        assertEquals(indexId, data.getIndexId());
        assertEquals(reactions, data.getReactions());
    }

    /**
     * 作用：集中校验撤回/删除这类“消息状态变更”广播。
     * 预期结果：事件类型必须与调用场景一致，
     * 且 data 中的 msgId、indexId、senderId 必须完整透传给前端。
     */
    private void assertRecallBroadcast(WsRespDTO wsRespDTO,
                                       WsRespDTOTypeEnum eventType,
                                       String roomId,
                                       String msgId,
                                       Long indexId,
                                       String senderId) {
        assertEquals(eventType.getType(), wsRespDTO.getType());
        assertEquals(roomId, wsRespDTO.getRoomId());
        MsgRecallRespDTO data = assertInstanceOf(MsgRecallRespDTO.class, wsRespDTO.getData());
        assertEquals(msgId, data.getMsgId());
        assertEquals(indexId, data.getIndexId());
        assertEquals(senderId, data.getSenderId());
    }
}
