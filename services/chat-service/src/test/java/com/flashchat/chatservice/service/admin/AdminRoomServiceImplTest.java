package com.flashchat.chatservice.service.admin;

import com.flashchat.channel.GameStateQueryService;
import com.flashchat.channel.event.MemberKickedFromRoomEvent;
import com.flashchat.chatservice.cache.RoomCacheKeys;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberMuteStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberRoleEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.dto.req.AdminRoomQueryReqDTO;
import com.flashchat.chatservice.dto.resp.AdminRoomSummaryRespDTO;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.service.SystemMessageService;
import com.flashchat.chatservice.service.UnreadService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.enums.AccountRoleEnum;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import com.flashchat.userservice.service.admin.AdminAuthService;
import com.flashchat.userservice.service.admin.AdminOperationLogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRoomServiceImplTest {

    @Mock
    private AdminAuthService adminAuthService;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Mock
    private RoomService roomService;

    @Mock
    private SystemMessageService systemMessageService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private RoomMapper roomMapper;

    @Mock
    private RoomChannelManager roomChannelManager;

    @Mock
    private UnreadService unreadService;

    @Mock
    private GameStateQueryService gameStateQueryService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private MultistageCacheProxy multistageCacheProxy;

    private AdminRoomService adminRoomService;

    @BeforeEach
    void setUp() {
        adminRoomService = new AdminRoomServiceImpl(
                adminAuthService,
                adminOperationLogService,
                roomService,
                systemMessageService,
                roomMemberService,
                roomMapper,
                roomChannelManager,
                unreadService,
                gameStateQueryService,
                applicationEventPublisher,
                multistageCacheProxy
        );
    }

    @Test
    void closeRoomShouldForceGraceSendSystemMessageAndRecordLog() {
        AccountDO admin = buildAdmin();
        RoomDO room = buildRoom();
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(roomService.getRoomByRoomId("room-1")).thenReturn(room);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("risk control");

        adminRoomService.closeRoom(1L, "room-1", request);

        verify(roomService).doForceGrace("room-1");
        verify(systemMessageService).sendToRoom("room-1", "该房间违规");
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void closeRoomShouldRejectRoomAlreadyInGrace() {
        AccountDO admin = buildAdmin();
        RoomDO room = buildRoom();
        room.setStatus(RoomStatusEnum.GRACE.getCode());
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(roomService.getRoomByRoomId("room-1")).thenReturn(room);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("repeat close");

        assertThrows(ClientException.class, () -> adminRoomService.closeRoom(1L, "room-1", request));
        verify(roomService, never()).doForceGrace("room-1");
        verify(systemMessageService, never()).sendToRoom(eq("room-1"), any());
        verify(adminOperationLogService, never()).record(any(AdminOperationLogDO.class));
    }

    @Test
    void searchRoomsShouldReturnPagedRoomSummaries() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());

        Page<RoomDO> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(
                buildRoom(),
                RoomDO.builder()
                        .id(11L)
                        .roomId("room-2")
                        .title("risk room")
                        .status(RoomStatusEnum.EXPIRING.getCode())
                        .currentMembers(4)
                        .maxMembers(12)
                        .isPublic(0)
                        .build()
        ));
        when(roomMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(roomChannelManager.getOnlineCountInRoom("room-1")).thenReturn(3);
        when(roomChannelManager.getOnlineCountInRoom("room-2")).thenReturn(1);

        AdminRoomQueryReqDTO request = new AdminRoomQueryReqDTO();
        request.setKeyword("room");
        request.setStatus(RoomStatusEnum.ACTIVE.getCode());
        request.setPage(1L);
        request.setSize(20L);

        AdminPageRespDTO<AdminRoomSummaryRespDTO> response = adminRoomService.searchRooms(1L, request);

        assertEquals(2L, response.getTotal());
        assertEquals(2, response.getRecords().size());
        assertEquals("room-1", response.getRecords().get(0).getRoomId());
        assertEquals(3, response.getRecords().get(0).getOnlineCount());
    }

    @Test
    void kickMemberShouldRejectHostTarget() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(roomService.getRoomByRoomId("room-1")).thenReturn(buildRoom());
        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-1", 2L))
                .thenReturn(buildRoomMember(2L, RoomMemberRoleEnum.HOST.getCode(), RoomMemberMuteStatusEnum.UNMUTE.getCode()));

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("risk control");

        assertThrows(ClientException.class, () -> adminRoomService.kickMember(1L, "room-1", 2L, request));
        verify(roomMemberService, never()).updateWithCacheEvict(any(RoomMemberDO.class));
    }

    @Test
    void kickMemberShouldKickActiveMemberAndRecordLog() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(roomService.getRoomByRoomId("room-1")).thenReturn(buildRoom());
        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-1", 2L))
                .thenReturn(buildRoomMember(2L, RoomMemberRoleEnum.MEMBER.getCode(), RoomMemberMuteStatusEnum.UNMUTE.getCode()));
        when(roomMemberService.updateWithCacheEvict(any(RoomMemberDO.class))).thenReturn(true);
        when(gameStateQueryService.isInPlayingGame("room-1", 2L)).thenReturn(false);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("risk control");

        adminRoomService.kickMember(1L, "room-1", 2L, request);

        verify(roomMapper).decrementMemberCount("room-1");
        verify(roomChannelManager).kickMember("room-1", 2L);
        verify(unreadService).removeRoomUnread(2L, "room-1");
        verify(multistageCacheProxy).delete(RoomCacheKeys.room("room-1"));
        verify(applicationEventPublisher).publishEvent(any(MemberKickedFromRoomEvent.class));
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void muteMemberShouldUpdateMuteStatusAndRecordLog() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(roomService.getRoomByRoomId("room-1")).thenReturn(buildRoom());
        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-1", 2L))
                .thenReturn(buildRoomMember(2L, RoomMemberRoleEnum.MEMBER.getCode(), RoomMemberMuteStatusEnum.UNMUTE.getCode()));
        when(roomMemberService.updateWithCacheEvict(any(RoomMemberDO.class))).thenReturn(true);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("mute member");

        adminRoomService.muteMember(1L, "room-1", 2L, request);

        verify(roomChannelManager).muteMember("room-1", 2L);
        verify(multistageCacheProxy).delete(RoomCacheKeys.room("room-1"));
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void unmuteMemberShouldUpdateMuteStatusAndRecordLog() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(roomService.getRoomByRoomId("room-1")).thenReturn(buildRoom());
        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-1", 2L))
                .thenReturn(buildRoomMember(2L, RoomMemberRoleEnum.MEMBER.getCode(), RoomMemberMuteStatusEnum.MUTE.getCode()));
        when(roomMemberService.updateWithCacheEvict(any(RoomMemberDO.class))).thenReturn(true);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("unmute member");

        adminRoomService.unmuteMember(1L, "room-1", 2L, request);

        verify(roomChannelManager).unmuteMember("room-1", 2L);
        verify(multistageCacheProxy).delete(RoomCacheKeys.room("room-1"));
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    private AccountDO buildAdmin() {
        return AccountDO.builder()
                .id(1L)
                .accountId("FC-ADMIN01")
                .nickname("admin")
                .systemRole(AccountRoleEnum.ADMIN.getCode())
                .status(AccountStatusEnum.NORMAL.getCode())
                .isRegistered(1)
                .build();
    }

    private RoomDO buildRoom() {
        return RoomDO.builder()
                .id(10L)
                .roomId("room-1")
                .title("demo room")
                .status(RoomStatusEnum.ACTIVE.getCode())
                .build();
    }

    private RoomMemberDO buildRoomMember(Long accountId, Integer role, Integer muteStatus) {
        return RoomMemberDO.builder()
                .id(100L + accountId)
                .roomId("room-1")
                .accountId(accountId)
                .role(role)
                .isMuted(muteStatus)
                .status(RoomMemberStatusEnum.ACTIVE.getCode())
                .build();
    }
}
