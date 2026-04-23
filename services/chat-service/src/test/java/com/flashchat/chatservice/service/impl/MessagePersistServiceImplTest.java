package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.config.MessageCryptoProperties;
import com.flashchat.chatservice.service.crypto.MessageContentCodec;
import com.flashchat.chatservice.service.crypto.MessageCryptoService;
import com.flashchat.convention.exception.ServiceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagePersistServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private MessageWindowServiceImpl messageWindowService;

    private MessagePersistServiceImpl messagePersistService;

    @BeforeEach
    void setUp() {
        messagePersistService = new MessagePersistServiceImpl(
                stringRedisTemplate, messageMapper, buildCodec(),
                messageWindowService, new SimpleMeterRegistry());
    }

    /**
     * 作用：验证同步写库路径不再吞异常。
     * 预期结果：当 DB insert 失败时，saveSync() 必须抛出 ServiceException，
     * 这样上层才能明确感知 durable handoff 失败，而不是误判为发送成功。
     */
    @Test
    void saveSyncShouldThrowWhenDbInsertFails() {
        when(messageMapper.insert(any(MessageDO.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(ServiceException.class, () -> messagePersistService.saveSync(buildMessage()));
    }

    /**
     * 作用：验证异步持久化路径在 Redis XADD 失败后会尝试降级同步写库，
     * 但如果同步写库也失败，最终仍然要把失败显式抛回上层。
     * 预期结果：saveAsync() 在“Redis down + DB down”的双失败场景下抛出 ServiceException，
     * 证明持久化层契约已经从“记日志就算了”变成“失败必须上抛”。
     */
    @Test
    void saveAsyncShouldThrowWhenXAddAndSyncFallbackBothFail() {
        doThrow(new RuntimeException("redis down"))
                .when(stringRedisTemplate)
                .execute(any(RedisCallback.class));
        when(messageMapper.insert(any(MessageDO.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(ServiceException.class, () -> messagePersistService.saveAsync(buildMessage()));
    }

    @Test
    void saveSyncShouldEncryptContentBeforeInsert() {
        messagePersistService.saveSync(buildMessage());

        ArgumentCaptor<MessageDO> captor = ArgumentCaptor.forClass(MessageDO.class);
        verify(messageMapper).insert(captor.capture());
        MessageDO stored = captor.getValue();
        Assertions.assertNull(stored.getContent());
        Assertions.assertNotNull(stored.getContentCipher());
        Assertions.assertNotNull(stored.getContentIv());
        Assertions.assertEquals(1, stored.getKeyVersion());
    }

    /**
     * 作用：构造一条最小可持久化消息，供异常路径测试复用。
     * 预期结果：返回的 MessageDO 字段完整，足以让 saveSync/saveAsync 走到真正的持久化分支。
     */
    private MessageDO buildMessage() {
        return MessageDO.builder()
                .id(100L)
                .msgId("msg-100")
                .roomId("room-1")
                .senderId(1L)
                .nickname("tester")
                .avatarColor("#000000")
                .content("hello")
                .msgType(1)
                .status(0)
                .isHost(0)
                .build();
    }

    private MessageContentCodec buildCodec() {
        MessageCryptoProperties properties = new MessageCryptoProperties();
        properties.setEnabled(true);
        properties.setKeyVersion(1);
        properties.setKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        return new MessageContentCodec(new MessageCryptoService(properties));
    }
}
