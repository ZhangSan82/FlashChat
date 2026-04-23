package com.flashchat.chatservice.websocket.manager;

import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.convention.storage.OssAssetUrlService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomChannelManagerTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private OssAssetUrlService ossAssetUrlService;

    private SimpleMeterRegistry meterRegistry;
    private RoomChannelManager roomChannelManager;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        roomChannelManager = new RoomChannelManager(
                meterRegistry,
                applicationEventPublisher,
                roomMemberService,
                ossAssetUrlService
        );
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    void isRoomMemberShouldFallbackToDbWhenMemoryMemberMissing() {
        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-1", 1L))
                .thenReturn(RoomMemberDO.builder()
                        .roomId("room-1")
                        .accountId(1L)
                        .status(RoomMemberStatusEnum.ACTIVE.getCode())
                        .build());

        assertTrue(roomChannelManager.isRoomMember("room-1", 1L));
    }

    @Test
    void isRoomMemberShouldRejectNonActiveDbMember() {
        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-1", 1L))
                .thenReturn(RoomMemberDO.builder()
                        .roomId("room-1")
                        .accountId(1L)
                        .status(RoomMemberStatusEnum.LEFT.getCode())
                        .build());

        assertFalse(roomChannelManager.isRoomMember("room-1", 1L));
    }
}
