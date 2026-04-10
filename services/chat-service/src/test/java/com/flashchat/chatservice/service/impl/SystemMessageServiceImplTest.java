package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.config.MsgIdGenerator;
import com.flashchat.chatservice.service.MessageSideEffectService;
import com.flashchat.chatservice.service.dispatch.RoomSerialLock;
import com.flashchat.convention.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemMessageServiceImplTest {

    @Mock
    private MsgIdGenerator msgIdGenerator;

    @Mock
    private MessagePersistServiceImpl messagePersistService;

    @Mock
    private MessageSideEffectService messageSideEffectService;

    private SystemMessageServiceImpl systemMessageService;

    @BeforeEach
    void setUp() {
        systemMessageService = new SystemMessageServiceImpl(
                msgIdGenerator,
                messagePersistService,
                messageSideEffectService,
                new RoomSerialLock()
        );
    }

    /**
     * 作用：验证系统消息链路和普通消息链路一样，
     * 只要 durable persist 失败，就必须立刻把失败抛回调用方。
     * 预期结果：sendToRoom() 抛出 ServiceException，
     * 同时 messageSideEffectService 不得发生任何交互，证明失败不会继续推进窗口和广播副作用。
     */
    @Test
    void sendToRoomShouldPropagateWhenDurablePersistFails() {
        when(msgIdGenerator.tryNextId()).thenReturn(10L);
        doThrow(new ServiceException("persist failed"))
                .when(messagePersistService)
                .saveAsync(any());

        assertThrows(ServiceException.class,
                () -> systemMessageService.sendToRoom("room-1", "system message"));

        verifyNoInteractions(messageSideEffectService);
    }
}
