package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageWindowServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private SimpleMeterRegistry meterRegistry;
    private MessageWindowServiceImpl messageWindowService;
    private AtomicLong fakeClock;
    private AtomicInteger luaExecuteCount;
    private AtomicInteger trimCallCount;
    private AtomicInteger expireCallCount;
    private AtomicReference<String> lastAddedKey;
    private AtomicReference<String> lastAddedScore;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        fakeClock = new AtomicLong(1_000L);
        luaExecuteCount = new AtomicInteger();
        trimCallCount = new AtomicInteger();
        expireCallCount = new AtomicInteger();
        lastAddedKey = new AtomicReference<>();
        lastAddedScore = new AtomicReference<>();

        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenAnswer(invocation -> {
                    luaExecuteCount.incrementAndGet();
                    List<String> keys = invocation.getArgument(1);
                    lastAddedKey.set(keys.get(0));
                    lastAddedScore.set(invocation.getArgument(2));
                    if ("1".equals(invocation.getArgument(4))) {
                        trimCallCount.incrementAndGet();
                    }
                    if ("1".equals(invocation.getArgument(5))) {
                        expireCallCount.incrementAndGet();
                    }
                    return 1L;
                });

        messageWindowService = new MessageWindowServiceImpl(stringRedisTemplate, meterRegistry, fakeClock::get);
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    void addToWindowShouldRecordSerializeAndRedisSubStepMetrics() {
        messageWindowService.addToWindow("room-1", 100L, buildMessage());

        assertEquals(1L, meterRegistry.get("flashchat.window.add.serialize.duration").timer().count());
        assertEquals(1L, meterRegistry.get("flashchat.window.add.redis.duration").timer().count());
        assertEquals(1, luaExecuteCount.get());
        assertEquals("flashchat:msg:window:room-1", lastAddedKey.get());
        assertEquals("100", lastAddedScore.get());
        assertEquals(0, trimCallCount.get());
        assertEquals(1, expireCallCount.get());
    }

    @Test
    void addToWindowShouldTrimOnlyOnConfiguredInterval() {
        for (int index = 0; index < 8; index += 1) {
            messageWindowService.addToWindow("room-1", 100L + index, buildMessage());
        }

        assertEquals(8, luaExecuteCount.get());
        assertEquals(1, trimCallCount.get());
        assertEquals(1, expireCallCount.get());
    }

    @Test
    void addToWindowShouldRefreshExpireOnlyAfterConfiguredInterval() {
        messageWindowService.addToWindow("room-1", 100L, buildMessage());
        fakeClock.addAndGet(59_000L);
        messageWindowService.addToWindow("room-1", 101L, buildMessage());
        fakeClock.addAndGet(1_000L);
        messageWindowService.addToWindow("room-1", 102L, buildMessage());

        assertEquals(3, luaExecuteCount.get());
        assertEquals(0, trimCallCount.get());
        assertEquals(2, expireCallCount.get());
    }

    private ChatBroadcastMsgRespDTO buildMessage() {
        return ChatBroadcastMsgRespDTO.builder()
                ._id("msg-100")
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
}
