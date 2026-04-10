package com.flashchat.chatservice.config;

import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.convention.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MsgIdGeneratorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private MessageMapper messageMapper;

    private MsgIdGenerator msgIdGenerator;

    @BeforeEach
    void setUp() {
        msgIdGenerator = new MsgIdGenerator(stringRedisTemplate, messageMapper);
    }

    /**
     * 作用：验证冷启动场景下，如果当前进程还没有拿到任何可信的 Redis 序号水位，
     * 就不允许直接走本地 fallback 发号。
     * 预期结果：fallbackNextId() 必须抛出 ServiceException，
     * 明确告诉上层“序号服务尚未就绪”，而不是冒险返回一个可能撞库的 ID。
     */
    @Test
    void fallbackNextIdShouldThrowWhenTrustedSequenceIsNotReady() {
        assertThrows(ServiceException.class, () -> msgIdGenerator.fallbackNextId());
    }

    /**
     * 作用：验证热降级场景下，只要当前进程已经成功从 Redis 拉到一段序号，
     * 本地 fallback 就必须沿着该段的可信上界继续推进,而不是回退到 DB 水位或 0。
     * 新分段模型:INCRBY 返回的是段末 ID,本地消费段内首个 ID,
     *            段末 ID 作为 lastKnownId 的可信上界;fallback 在此之上 +1。
     * 预期:Redis 脚本返回 200 → 本次发出段内首个 ID 101,
     *      lastKnownId 推到 200,fallback 返回 201。
     */
    @Test
    void fallbackNextIdShouldContinueAfterRedisAllocationSucceeds() {
        when(messageMapper.selectMaxId()).thenReturn(0L);
        // 模拟 Lua 脚本原子 INIT + INCRBY 100 后返回段末 ID = 200
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(200L);

        Long redisAllocatedId = msgIdGenerator.tryNextId();
        Long fallbackId = msgIdGenerator.fallbackNextId();

        assertEquals(101L, redisAllocatedId, "段 [101, 200] 的第一个 ID");
        assertEquals(201L, fallbackId, "fallback 必须跨过整个段末,回避段内空洞风险");
    }

    /**
     * 作用:验证 Redis 执行 Lua 脚本抛异常时,tryNextId 返回 null,
     * 且本地水位不会被污染。调用方应该走 fallbackNextId 或 saveSync 降级路径。
     */
    @Test
    void tryNextIdShouldReturnNullWhenRedisThrows() {
        when(messageMapper.selectMaxId()).thenReturn(100L);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        Long id = msgIdGenerator.tryNextId();

        assertNull(id);
        // 水位尚未建立可信状态,fallback 应该拒绝
        assertThrows(ServiceException.class, () -> msgIdGenerator.fallbackNextId());
    }
}
