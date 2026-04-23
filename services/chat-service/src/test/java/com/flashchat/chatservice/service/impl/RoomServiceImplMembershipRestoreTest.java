package com.flashchat.chatservice.service.impl;

import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.channel.GameStateQueryService;
import com.flashchat.chatservice.cache.RoomCacheGetFilter;
import com.flashchat.chatservice.cache.RoomCacheIfAbsentHandler;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberMuteStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberRoleEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.delay.producer.RoomDelayProducer;
import com.flashchat.chatservice.service.MessageWindowService;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.service.UnreadService;
import com.flashchat.chatservice.service.crypto.MessageContentCodec;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.convention.storage.OssAssetUrlService;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceImplMembershipRestoreTest {

    @Mock
    private RoomChannelManager roomChannelManager;

    @Mock
    private RBloomFilter<String> flashChatRoomRegisterCachePenetrationBloomFilter;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private UnreadService unreadService;

    @Mock
    private AccountService accountService;

    @Mock
    private MultistageCacheProxy multistageCacheProxy;

    @Mock
    private RoomDelayProducer roomDelayProducer;

    @Mock
    private MessageWindowService messageWindowService;

    @Mock
    private CreditService creditService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private RoomMapper roomMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private GameStateQueryService gameStateQueryService;

    @Mock
    private RoomCacheGetFilter roomCacheGetFilter;

    @Mock
    private RoomCacheIfAbsentHandler roomCacheIfAbsentHandler;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private MessageContentCodec messageContentCodec;

    @Mock
    private OssAssetUrlService ossAssetUrlService;

    private RoomServiceImpl roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomServiceImpl(
                roomChannelManager,
                flashChatRoomRegisterCachePenetrationBloomFilter,
                roomMemberService,
                unreadService,
                accountService,
                multistageCacheProxy,
                roomDelayProducer,
                messageWindowService,
                creditService,
                transactionTemplate,
                roomMapper,
                messageMapper,
                applicationEventPublisher,
                gameStateQueryService,
                roomCacheGetFilter,
                roomCacheIfAbsentHandler,
                stringRedisTemplate,
                messageContentCodec,
                ossAssetUrlService
        );
    }

    @Test
    void restoreActiveMemberToRoomMemoryIfNeededShouldJoinSilentlyWhenMemoryIsMissing() {
        AccountDO account = new AccountDO();
        account.setNickname("alice");
        account.setAvatarColor("#112233");

        RoomMemberDO activeMember = RoomMemberDO.builder()
                .roomId("room-1")
                .accountId(1L)
                .role(RoomMemberRoleEnum.MEMBER.getCode())
                .isMuted(RoomMemberMuteStatusEnum.MUTE.getCode())
                .status(RoomMemberStatusEnum.ACTIVE.getCode())
                .build();

        when(roomChannelManager.isInRoom("room-1", 1L)).thenReturn(false);
        when(roomMemberService.getRoomMemberByRoomIdAndAccountId("room-1", 1L)).thenReturn(activeMember);

        roomService.restoreActiveMemberToRoomMemoryIfNeeded("room-1", 1L, account);

        verify(roomChannelManager).joinRoomSilent("room-1", 1L, "alice", "#112233", false, true);
    }

    @Test
    void restoreActiveMemberToRoomMemoryIfNeededShouldSkipWhenMemberAlreadyInMemory() {
        AccountDO account = new AccountDO();
        account.setNickname("alice");
        account.setAvatarColor("#112233");

        when(roomChannelManager.isInRoom("room-1", 1L)).thenReturn(true);

        roomService.restoreActiveMemberToRoomMemoryIfNeeded("room-1", 1L, account);

        verify(roomMemberService, never()).getRoomMemberByRoomIdAndAccountId("room-1", 1L);
        verify(roomChannelManager, never()).joinRoomSilent("room-1", 1L, "alice", "#112233", false, true);
    }
}
