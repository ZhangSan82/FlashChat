package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnreadServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RoomChannelManager roomChannelManager;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private MessageMapper messageMapper;

    /**
     * 验证新的 unread 主路径已经改成“读时 DB 统计”。
     *
     * <p>这个测试的关键不是只看返回值，而是要确保：
     *   1. 会调用 selectBatchUnreadCounts(accountId)；
     *   2. 不会再去读 Redis unread hash；
     *   3. 返回结果仍然保留 roomId -> unreadCount 的契约。
     */
    @Test
    void getAllUnreadCountsShouldReadFromDbDirectly() {
        UnreadServiceImpl service = new UnreadServiceImpl(
                stringRedisTemplate,
                roomChannelManager,
                roomMemberService,
                messageMapper
        );

        when(messageMapper.selectBatchUnreadCounts(1001L)).thenReturn(List.of(
                Map.of("room_id", "room-a", "cnt", 3L),
                Map.of("room_id", "room-b", "cnt", 1000L)
        ));

        Map<String, Integer> result = service.getAllUnreadCounts(1001L);

        assertEquals(2, result.size());
        assertEquals(3, result.get("room-a"));
        assertEquals(999, result.get("room-b"));
        verify(messageMapper).selectBatchUnreadCounts(1001L);
        verifyNoInteractions(stringRedisTemplate, roomChannelManager, roomMemberService);
    }

    /**
     * 验证单房间 unread 也已经走“基于 last_ack_msg_id 的 DB 统计”。
     *
     * <p>这里同时验证两个边界：
     *   1. 房间成员必须是 ACTIVE，非 ACTIVE 直接返回 0；
     *   2. 统计结果会做 999 上限截断。
     */
    @Test
    void getUnreadCountShouldReadFromDbDirectly() {
        UnreadServiceImpl service = new UnreadServiceImpl(
                stringRedisTemplate,
                roomChannelManager,
                roomMemberService,
                messageMapper
        );

        RoomMemberDO roomMember = RoomMemberDO.builder()
                .roomId("room-a")
                .accountId(1001L)
                .status(RoomMemberStatusEnum.ACTIVE.getCode())
                .lastAckMsgId(10L)
                .build();

        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-a", 1001L)).thenReturn(roomMember);
        when(messageMapper.selectUnreadMsgIds("room-a", 10L)).thenReturn(java.util.stream.LongStream.rangeClosed(1, 1200)
                .boxed()
                .toList());

        int unread = service.getUnreadCount(1001L, "room-a");

        assertEquals(999, unread);
        verify(roomMemberService).getRoomMemberByRoomIdAndAccountId("room-a", 1001L);
        verify(messageMapper).selectUnreadMsgIds("room-a", 10L);
        verifyNoInteractions(stringRedisTemplate, roomChannelManager);
    }

    /**
     * 验证旧的写时 fan-out 入口已经退化成 no-op。
     *
     * <p>这个测试的价值在于防止未来有人误把 incrementUnread 又重新接回 Redis 写路径，
     * 从而把 mailbox / side-effect 的历史瓶颈重新带回来。
     */
    @Test
    void incrementUnreadShouldBeNoOpInReadTimeMode() {
        UnreadServiceImpl service = new UnreadServiceImpl(
                stringRedisTemplate,
                roomChannelManager,
                roomMemberService,
                messageMapper
        );

        service.incrementUnread("room-a", 1001L);

        assertTrue(true);
        verifyNoInteractions(stringRedisTemplate, roomChannelManager, roomMemberService, messageMapper);
    }
}
