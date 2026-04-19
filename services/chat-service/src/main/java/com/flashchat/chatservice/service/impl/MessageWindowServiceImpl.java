package com.flashchat.chatservice.service.impl;

import com.alibaba.fastjson2.JSON;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.WindowQueryResult;
import com.flashchat.chatservice.service.MessageWindowService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 消息滑动窗口 - Redis Sorted Set 实现
 * <p>
 * Redis key: flashchat:msg:window:{roomId}
 * score: 消息的 dbId（MsgIdGenerator 生成的全局递增 ID）
 * member: 消息的展示 JSON（ChatBroadcastMsgRespDTO 序列化）
 * 降级策略: 写入失败标记降级（per-room），5 分钟后自动恢复尝试；
 * 写入成功时主动清除降级标记，Redis 闪断恢复后立刻恢复窗口查询。
 */
@Slf4j
@Service
public class MessageWindowServiceImpl implements MessageWindowService {

    private static final String WINDOW_KEY_PREFIX = "flashchat:msg:window:";
    private static final int WINDOW_SIZE = 100;
    private static final long WINDOW_TTL_SECONDS = 24 * 3600L;
    private static final int WINDOW_TRIM_INTERVAL = 8;
    private static final long WINDOW_TTL_REFRESH_INTERVAL_MS = 60_000L;
    private static final long DEGRADE_RECOVER_MS = 5 * 60 * 1000L;

    /**
     * Lua 脚本：窗口写入（ZADD + 条件 trim + 条件 expire）
     * 从 classpath:lua/window_add.lua 加载
     */
    /*
     * window_add 鐨?Lua 鐗堟湰鏆傛椂鍋滅敤锛屼负浜哸/BC 瀵规瘮锛岃剼鏈枃浠朵繚鐣欙紝浣嗚皟鐢ㄨ矾寰勫凡鍥為€€鍒?pipeline銆?
     * private static final DefaultRedisScript<Long> ADD_SCRIPT;
     */

    /**
     * Lua 脚本：按 score 替换窗口中的 member
     * 从 classpath:lua/window_update_by_score.lua 加载
     */
    private static final DefaultRedisScript<Long> ADD_SCRIPT;
    private static final DefaultRedisScript<Long> UPDATE_SCRIPT;

    /**
     * Lua 脚本：按 score 移除窗口中的 member
     * 从 classpath:lua/window_remove_by_score.lua 加载
     */
    private static final DefaultRedisScript<Long> REMOVE_SCRIPT;

    static {
        /*
        ADD_SCRIPT = new DefaultRedisScript<>();
        ADD_SCRIPT.setLocation(new ClassPathResource("lua/window_add.lua"));
        ADD_SCRIPT.setResultType(Long.class);
         */

        ADD_SCRIPT = new DefaultRedisScript<>();
        ADD_SCRIPT.setLocation(new ClassPathResource("lua/window_add.lua"));
        ADD_SCRIPT.setResultType(Long.class);

        UPDATE_SCRIPT = new DefaultRedisScript<>();
        UPDATE_SCRIPT.setLocation(new ClassPathResource("lua/window_update_by_score.lua"));
        UPDATE_SCRIPT.setResultType(Long.class);

        REMOVE_SCRIPT = new DefaultRedisScript<>();
        REMOVE_SCRIPT.setLocation(new ClassPathResource("lua/window_remove_by_score.lua"));
        REMOVE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final Timer windowAddSerializeTimer;
    private final Timer windowAddRedisTimer;
    private final LongSupplier currentTimeMillisSupplier;

    /**
     * per-room 降级标记：roomId -> 降级时间戳
     * 5 分钟后自动恢复（懒检查模式）
     */
    private final ConcurrentHashMap<String, Long> degradedRooms = new ConcurrentHashMap<>();

    /**
     * per-room 写入节流状态。
     * 这里只控制 trim / expire 的触发频率，不改变 ZADD 每条消息都写入的语义。
     */
    private final ConcurrentHashMap<String, WindowWriteState> windowWriteStates = new ConcurrentHashMap<>();

    @Autowired
    public MessageWindowServiceImpl(StringRedisTemplate stringRedisTemplate, MeterRegistry meterRegistry) {
        this(stringRedisTemplate, meterRegistry, System::currentTimeMillis);
    }

    MessageWindowServiceImpl(StringRedisTemplate stringRedisTemplate,
                             MeterRegistry meterRegistry,
                             LongSupplier currentTimeMillisSupplier) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.currentTimeMillisSupplier = currentTimeMillisSupplier;
        if (meterRegistry != null) {
            this.windowAddSerializeTimer = Timer.builder("flashchat.window.add.serialize.duration")
                    .description("Time spent serializing window_add payload before Redis write")
                    .register(meterRegistry);
            this.windowAddRedisTimer = Timer.builder("flashchat.window.add.redis.duration")
                    .description("Time spent executing Redis write for window_add")
                    .register(meterRegistry);
        } else {
            this.windowAddSerializeTimer = null;
            this.windowAddRedisTimer = null;
        }
    }

    MessageWindowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, null, System::currentTimeMillis);
    }

    private String buildKey(String roomId) {
        return WINDOW_KEY_PREFIX + roomId;
    }

    private static <T> T record(Timer timer, Supplier<T> action) {
        if (timer != null) {
            return timer.record(action::get);
        }
        return action.get();
    }

    // ==================== 写入 ====================

    /**
     * 旧逻辑：Pipeline 合并三个命令为一次网络往返（<1ms）
     * <p>
     * ZADD + ZREMRANGEBYRANK（裁剪到 WINDOW_SIZE）+ EXPIRE（刷新 TTL）
     * 失败不阻塞发消息流程，仅标记降级。
     * 成功时主动清除降级标记。
     * <p>
     * 当前逻辑：
     * 1. ZADD 仍然每条消息都执行，保证窗口写入语义不变。
     * 2. trim 改为每 WINDOW_TRIM_INTERVAL 条触发一次。
     * 3. expire 改为每 WINDOW_TTL_REFRESH_INTERVAL_MS 刷新一次。
     * 4. Java 侧只负责决定这次是否 trim / 是否刷新 TTL；
     *    Redis 侧通过 Lua 一次原子执行 ZADD + 条件 trim + 条件 expire。
     * <p>
     * 也就是说，旧的关键语义和旧的优化背景说明保留，只是执行方式从 pipeline 换成了 Lua。
     */
    @Override
    public void addToWindow(String roomId, Long dbId, ChatBroadcastMsgRespDTO msg) {
        String key = buildKey(roomId);
        String json = record(windowAddSerializeTimer, () -> JSON.toJSONString(msg));

        WindowWriteState writeState = windowWriteStates.computeIfAbsent(roomId, ignored -> new WindowWriteState());
        boolean shouldTrim = writeState.shouldTrim(WINDOW_TRIM_INTERVAL);
        boolean shouldRefreshTtl = writeState.shouldRefreshExpire(
                currentTimeMillisSupplier.getAsLong(),
                WINDOW_TTL_REFRESH_INTERVAL_MS);

        try {
            record(windowAddRedisTimer, () -> {
                // 旧逻辑（保留说明）：
                // 1. ZADD score=dbId member=json
                // 2. 裁剪：ZREMRANGEBYRANK key 0 -(WINDOW_SIZE+1)
                //    删除排名最靠前（最旧）的条目，只保留最新 WINDOW_SIZE 条
                // 3. 刷新 TTL
                //
                // 新逻辑：
                // 仍然保留上述三步语义，但由 Lua 统一在 Redis 端执行；
                // trim / expire 是否触发，仍由当前房间的写入节流状态决定。
                /*
                stringRedisTemplate.execute(
                        ADD_SCRIPT,
                        List.of(key),
                        dbId.toString(),
                        json,
                        shouldTrim ? "1" : "0",
                        shouldRefreshTtl ? "1" : "0",
                        Integer.toString(WINDOW_SIZE),
                        Long.toString(WINDOW_TTL_SECONDS)
                );
                 */
                stringRedisTemplate.execute(
                        ADD_SCRIPT,
                        List.of(key),
                        dbId.toString(),
                        json,
                        shouldTrim ? "1" : "0",
                        shouldRefreshTtl ? "1" : "0",
                        Integer.toString(WINDOW_SIZE),
                        Long.toString(WINDOW_TTL_SECONDS)
                );
                /*
                 * 回退到 pipeline 的旧实现保留如下，便于后续继续做 A/B：
                 * stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                 *     @Override
                 *     @SuppressWarnings("unchecked")
                 *     public Object execute(RedisOperations operations) {
                 *         operations.opsForZSet().add(key, json, dbId.doubleValue());
                 *         if (shouldTrim) {
                 *             operations.opsForZSet().removeRange(key, 0, -(WINDOW_SIZE + 1L));
                 *         }
                 *         if (shouldRefreshTtl) {
                 *             operations.expire(key, WINDOW_TTL_SECONDS, TimeUnit.SECONDS);
                 *         }
                 *         return null;
                 *     }
                 * });
                 */
                return null;
            });

            degradedRooms.remove(roomId);
            log.debug("[窗口写入] room={}, dbId={}", roomId, dbId);
        } catch (Exception e) {
            log.warn("[窗口写入失败] room={}, dbId={}, 标记降级", roomId, dbId, e);
            degradedRooms.put(roomId, System.currentTimeMillis());
        }
    }

    // ==================== 读取 - 历史消息 ====================

    /**
     * 从窗口查历史消息
     * <p>
     * 内部处理流程（最多 2 次 Redis 调用）：
     * <ol>
     *   <li>ZRANGE key 0 0 WITHSCORES - 获取窗口最小 score（兼做 EXISTS 判断）</li>
     *   <li>ZREVRANGEBYSCORE - 倒序取 pageSize 条</li>
     * </ol>
     * <p>
     * 返回 null 的场景（全部降级到 DB）：
     * <ul>
     *   <li>房间在降级集合中（5 分钟内写入失败过）</li>
     *   <li>窗口 key 不存在（Redis 重启 / 房间刚创建还没消息）</li>
     *   <li>cursor <= 窗口最小 score（用户翻到窗口覆盖不了的老消息）</li>
     *   <li>Redis 查询异常</li>
     * </ul>
     */
    @Override
    public WindowQueryResult getHistoryFromWindow(String roomId, Long cursor, int pageSize) {
        if (isDegraded(roomId)) {
            return null;
        }

        String key = buildKey(roomId);

        try {
            // ===== Step 1: 获取窗口最小 score =====
            // 用 ZRANGE key 0 0 取排名最低（score 最小）的元素
            // 如果返回为空 -> 窗口不存在 -> 降级
            Set<ZSetOperations.TypedTuple<String>> minTuples =
                    stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, 0);

            if (minTuples == null || minTuples.isEmpty()) {
                return null;
            }

            Double minScoreDouble = minTuples.iterator().next().getScore();
            Long windowMinScore = (minScoreDouble != null) ? minScoreDouble.longValue() : null;

            // ===== Step 2: cursor 范围检查 =====
            // 用 <= 而非 <：cursor == windowMinScore 时语义是"要看比窗口最旧消息更老的消息"
            // 窗口内不可能有 score < windowMinScore 的数据，直接降级 DB
            if (cursor != null && windowMinScore != null && cursor <= windowMinScore) {
                return null;
            }

            // ===== Step 3: 查询 =====
            Set<ZSetOperations.TypedTuple<String>> tuples;
            if (cursor == null) {
                // 首次加载：取全范围倒序前 pageSize 条
                tuples = stringRedisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(
                                key,
                                Double.NEGATIVE_INFINITY,
                                Double.POSITIVE_INFINITY,
                                0,
                                pageSize
                        );
            } else {
                // 翻页：score < cursor（用 cursor - 1 模拟开区间，score 是整数所以等价）
                tuples = stringRedisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(
                                key,
                                Double.NEGATIVE_INFINITY,
                                cursor.doubleValue() - 1,
                                0,
                                pageSize
                        );
            }

            // ===== Step 4: 反序列化 =====
            List<ChatBroadcastMsgRespDTO> messages;
            if (tuples == null || tuples.isEmpty()) {
                messages = List.of();
            } else {
                messages = tuples.stream()
                        .map(tuple -> JSON.parseObject(tuple.getValue(), ChatBroadcastMsgRespDTO.class))
                        .collect(Collectors.toList());
            }

            return WindowQueryResult.builder()
                    .messages(messages)
                    .windowMinScore(windowMinScore)
                    .build();
        } catch (Exception e) {
            log.warn("[窗口读历史失败] room={}, cursor={}, 降级到 DB", roomId, cursor, e);
            degradedRooms.put(roomId, System.currentTimeMillis());
            return null;
        }
    }

    // ==================== 读取 - 新消息 ====================

    /**
     * 从窗口拉新消息（断线重连场景）
     * <p>
     * 用 ZRANGEBYSCORE 正序取 lastAckMsgId 之后的所有消息。
     * 返回 null 表示窗口不可用（降级到 DB）。
     */
    @Override
    public List<ChatBroadcastMsgRespDTO> getNewFromWindow(String roomId, Long lastAckMsgId) {
        if (isDegraded(roomId)) {
            return null;
        }

        String key = buildKey(roomId);

        try {
            // 先检查窗口是否存在 + lastAckMsgId 是否在窗口范围内
            Set<ZSetOperations.TypedTuple<String>> minTuples =
                    stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, 0);

            if (minTuples == null || minTuples.isEmpty()) {
                return null;
            }

            Double minScoreDouble = minTuples.iterator().next().getScore();
            if (minScoreDouble != null && lastAckMsgId < minScoreDouble.longValue()) {
                // lastAckMsgId 在窗口之外（用户离线太久，新消息超过 100 条），降级 DB
                return null;
            }

            // ZRANGEBYSCORE key (lastAckMsgId +inf -> 正序取所有新消息
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .rangeByScore(
                            key,
                            lastAckMsgId.doubleValue() + 1,
                            Double.POSITIVE_INFINITY
                    );

            if (members == null || members.isEmpty()) {
                return List.of();
            }

            return members.stream()
                    .map(json -> JSON.parseObject(json, ChatBroadcastMsgRespDTO.class))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[窗口拉新失败] room={}, lastAck={}, 降级到 DB", roomId, lastAckMsgId, e);
            degradedRooms.put(roomId, System.currentTimeMillis());
            return null;
        }
    }

    @Override
    public List<ChatBroadcastMsgRespDTO> getLatestFromWindow(String roomId, int limit) {
        if (isDegraded(roomId)) {
            return null;
        }

        String key = buildKey(roomId);
        int safeLimit = Math.max(1, Math.min(limit, 50));

        try {
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .reverseRange(key, 0, safeLimit - 1L);

            if (members == null || members.isEmpty()) {
                return List.of();
            }

            List<ChatBroadcastMsgRespDTO> list = members.stream()
                    .map(json -> JSON.parseObject(json, ChatBroadcastMsgRespDTO.class))
                    .collect(Collectors.toList());

            // reverseRange returns newest-first; preview UI needs oldest->newest.
            Collections.reverse(list);
            return list;
        } catch (Exception e) {
            log.warn("[窗口取最新失败] room={}, limit={}, 降级到 DB", roomId, limit, e);
            degradedRooms.put(roomId, System.currentTimeMillis());
            return null;
        }
    }

    // ==================== 清理 ====================

    @Override
    public void deleteWindow(String roomId) {
        try {
            stringRedisTemplate.delete(buildKey(roomId));
            degradedRooms.remove(roomId);
            windowWriteStates.remove(roomId);
            log.info("[窗口删除] room={}", roomId);
        } catch (Exception e) {
            log.warn("[窗口删除失败] room={}, TTL 24h 兜底清理", roomId, e);
        }
    }

    @Override
    public void updateMemberByScore(String roomId, Long score, String newJson) {
        if (roomId == null || score == null || newJson == null) {
            return;
        }

        String windowKey = buildKey(roomId);
        try {
            stringRedisTemplate.execute(
                    UPDATE_SCRIPT,
                    List.of(windowKey),
                    score.toString(),
                    newJson
            );
            log.debug("[窗口更新] room={}, score={}", roomId, score);
        } catch (Exception e) {
            log.error("[窗口更新失败] room={}, score={}", roomId, score, e);
        }
    }

    @Override
    public void removeMemberByScore(String roomId, Long score) {
        if (roomId == null || score == null) {
            return;
        }

        String windowKey = buildKey(roomId);
        try {
            stringRedisTemplate.execute(
                    REMOVE_SCRIPT,
                    List.of(windowKey),
                    score.toString()
            );
            log.debug("[窗口移除] room={}, score={}", roomId, score);
        } catch (Exception e) {
            log.error("[窗口移除失败] room={}, score={}", roomId, score, e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 检查降级状态，超过 5 分钟自动恢复
     */
    private boolean isDegraded(String roomId) {
        Long degradeTime = degradedRooms.get(roomId);
        if (degradeTime == null) {
            return false;
        }
        if (System.currentTimeMillis() - degradeTime > DEGRADE_RECOVER_MS) {
            degradedRooms.remove(roomId);
            return false;
        }
        return true;
    }

    private static final class WindowWriteState {
        private int writesSinceTrim = 0;
        private long lastExpireRefreshAtMs = 0L;

        private synchronized boolean shouldTrim(int interval) {
            writesSinceTrim += 1;
            if (writesSinceTrim >= interval) {
                writesSinceTrim = 0;
                return true;
            }
            return false;
        }

        private synchronized boolean shouldRefreshExpire(long nowMs, long intervalMs) {
            if (lastExpireRefreshAtMs == 0L || nowMs - lastExpireRefreshAtMs >= intervalMs) {
                lastExpireRefreshAtMs = nowMs;
                return true;
            }
            return false;
        }
    }
}
