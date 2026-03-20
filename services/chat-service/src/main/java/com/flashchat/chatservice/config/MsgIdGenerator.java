package com.flashchat.chatservice.config;

import com.flashchat.chatservice.dao.mapper.MessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息顺序 ID 生成器
 * 解决的核心矛盾：
 *   异步写 DB 时广播消息没有 dbId → ACK / 游标分页 / 离线消息全断
 * 三层保障：
 *   正常路径 → Redis INCR 预分配 ID + 异步攒批写 DB（高吞吐）
 *   重启路径 → Lua 脚本 + DB 水位恢复（防 ID 回退）
 *   降级路径 → lastKnownId 本地递增 + 同步写 DB（保可用性）
 * 关键设计决策：
 *   1. 全局单 key（flashchat:msg_seq:global），因为 t_message 是单表全局主键
 *   2. 降级不用 MySQL 自增（存在 ID 空间冲突），用 AtomicLong lastKnownId
 *   3. Lua 脚本原子执行 EXISTS + SET + INCR，消除并发初始化窗口
 */
@Slf4j
@Component
public class MsgIdGenerator {

    private final StringRedisTemplate stringRedisTemplate;
    private final MessageMapper messageMapper;

    /**
     * 全局唯一的 Redis Key
     */
    private static final String REDIS_KEY = "flashchat:msg_seq:global";

    /**
     * 降级用的本地计数器
     * 每次 Redis INCR 成功都更新此值
     * Redis 不可用时从此值继续递增，保证 ID 一定大于所有已分配 ID
     *
     * 为什么不用 MySQL 自增做降级：
     *   Redis 预分配的 ID 可能超前于 DB 实际写入（攒批还没 flush）
     *   此时 MySQL 自增指针落后于 Redis，降级分配的 ID 会和攒批队列中的 ID 冲突
     */
    private final AtomicLong lastKnownId = new AtomicLong(0);

    /**
     * Lua 脚本：原子执行「不存在则初始化 + 递增」
     * Redis 单线程执行 Lua，脚本内的 EXISTS + SET + INCR 不会被其他命令打断
     * 彻底消除并发初始化窗口（多线程同时发现 key 不存在时不会互相覆盖）
     * KEYS[1] = flashchat:msg_seq:global
     * ARGV[1] = DB 水位值（SELECT MAX(id) 的结果）
     */
    private static final DefaultRedisScript<Long> INIT_AND_INCR;

    static {
        INIT_AND_INCR = new DefaultRedisScript<>();
        INIT_AND_INCR.setScriptText(
                // key 不存在 → 用 DB 水位初始化
                "if redis.call('EXISTS', KEYS[1]) == 0 then " +
                        "  redis.call('SET', KEYS[1], ARGV[1]) " +
                        "end " +
                        // 无论是否刚初始化，都递增并返回
                        "return redis.call('INCR', KEYS[1])"
        );
        INIT_AND_INCR.setResultType(Long.class);
    }

    public MsgIdGenerator(StringRedisTemplate stringRedisTemplate,
                          MessageMapper messageMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.messageMapper = messageMapper;
    }

    /**
     * 尝试获取下一个消息顺序 ID
     * @return 正数 ID（成功）或 null（Redis 不可用，调用方需走降级路径）
     */
    public Long tryNextId() {
        try {
            // ====== 快速路径：key 存在 → 直接 INCR ======
            // 99.9% 的请求走这里，一次 hasKey + 一次 INCR = 两次 Redis 调用
            // hasKey 的作用是避免每次都查 DB，不是避免 Lua
            // 边界条件：hasKey 和 INCR 之间 key 被删除（FLUSHDB），INCR 会从 0 开始
            //   但 key 没有 TTL，FLUSHDB 是运维事故级别，可接受
            Boolean exists = stringRedisTemplate.hasKey(REDIS_KEY);
            if (Boolean.TRUE.equals(exists)) {
                Long id = stringRedisTemplate.opsForValue().increment(REDIS_KEY);
                if (id != null) {
                    lastKnownId.updateAndGet(current -> Math.max(current, id));
                    return id;
                }
            }

            // ====== 慢路径：key 不存在 → 查 DB 水位 + Lua 原子初始化 ======
            // 触发时机：Redis 重启后的第一条消息
            // SELECT MAX(id) FROM t_message（全表，不按房间）
            Long maxId = messageMapper.selectMaxId();
            long floor = (maxId != null) ? maxId : 0L;

            log.info("[消息ID-慢路径] key 不存在，从 DB 恢复水位={}", floor);

            Long id = stringRedisTemplate.execute(
                    INIT_AND_INCR,
                    List.of(REDIS_KEY),
                    String.valueOf(floor)
            );

            if (id != null) {
                lastKnownId.updateAndGet(current -> Math.max(current, id));
                return id;
            }

            return null;

        } catch (Exception e) {
            log.error("[消息ID-Redis异常] 降级到本地递增", e);
            return null;
        }
    }

    /**
     * 降级路径：Redis 不可用时，从本地 lastKnownId 继续递增
     * 保证：降级 ID 一定大于所有已通过 Redis 分配的 ID
     * 原因：lastKnownId 在每次 Redis INCR 成功时都会更新为最大值
     * @return 一定大于之前所有 ID 的正数
     */
    public Long fallbackNextId() {
        return lastKnownId.incrementAndGet();
    }
}