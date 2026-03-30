package com.flashchat.chatservice.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息发送限流器 — Redis Lua 滑动窗口实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RateLimitProperties.class)
public class RedisMessageRateLimiter implements MessageRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimitProperties properties;

    private static final String KEY_PREFIX = "flashchat:rl:";

    /**
     * 单机内全局递增序列
     */
    private static final AtomicLong NONCE_SEQ = new AtomicLong(0);

    /**
     * Lua 脚本
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(
                new ClassPathResource("lua/rate_limit_multi.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Override
    public RateLimitResult checkLimit(Long senderId, String roomId) {
        // ===== 总开关 =====
        if (!properties.isEnabled()) {
            return RateLimitResult.PASS;
        }

        // ===== 参数防御 =====
        if (senderId == null || roomId == null || roomId.isBlank()) {
            log.error("[限流] 参数异常, senderId={}, roomId={}, 降级放行",
                    senderId, roomId);
            return RateLimitResult.PASS;
        }

        try {
            return doCheck(senderId, roomId);
        } catch (Exception e) {
            // ===== 降级：Redis 异常放行 =====
            log.error("[限流] Redis 异常, senderId={}, roomId={}, 降级放行",
                    senderId, roomId, e);
            return RateLimitResult.PASS;
        }
    }

    /**
     * 执行 Lua 脚本进行三维度限流检查
     */
    private RateLimitResult doCheck(Long senderId, String roomId) {
        // ===== 1. 构建三个维度的 Key =====
        String userGlobalKey = KEY_PREFIX + "u:" + senderId;
        String userRoomKey = KEY_PREFIX + "ur:" + senderId + ":" + roomId;
        String roomGlobalKey = KEY_PREFIX + "r:" + roomId;

        List<String> keys = List.of(userGlobalKey, userRoomKey, roomGlobalKey);

        // ===== 2. 构建参数 =====
        RateLimitProperties.DimensionConfig ug = properties.getUserGlobal();
        RateLimitProperties.DimensionConfig ur = properties.getUserRoom();
        RateLimitProperties.DimensionConfig rg = properties.getRoomGlobal();

        String now = String.valueOf(System.currentTimeMillis());

        // AtomicLong 自增序列，单机内绝对唯一
        // 拼上时间戳是为了跨机器场景下降低碰撞概率
        String nonce = now + ":" + NONCE_SEQ.incrementAndGet();

        // ===== 3. 执行 Lua 脚本 =====
        Long luaResult = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                keys,
                now,
                nonce,
                "3",
                String.valueOf(ug.getWindowMs()), String.valueOf(ug.getMaxCount()),
                String.valueOf(ur.getWindowMs()), String.valueOf(ur.getMaxCount()),
                String.valueOf(rg.getWindowMs()), String.valueOf(rg.getMaxCount())
        );

        // ===== 4. 映射结果 =====
        int resultCode = (luaResult != null) ? luaResult.intValue() : 0;
        RateLimitResult result = RateLimitResult.of(resultCode);

        if (result == null) {
            // Lua 返回了预期之外的值，说明脚本逻辑可能有问题
            log.warn("[限流] Lua 脚本返回未知值: {}, senderId={}, roomId={}, 降级放行",
                    resultCode, senderId, roomId);
            return RateLimitResult.PASS;
        }

        // ===== 5. 记录限流命中日志 =====
        if (!result.isPassed()) {
            log.warn("[限流] 触发限流, senderId={}, roomId={}, dimension={}({})",
                    senderId, roomId, result.name(), result.getMessage());
        }

        return result;
    }
}
