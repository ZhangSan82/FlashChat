package com.flashchat.chatservice.service.impl;

import com.alibaba.fastjson2.JSON;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.WindowQueryResult;
import com.flashchat.chatservice.service.MessageWindowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
@RequiredArgsConstructor
public class MessageWindowServiceImpl implements MessageWindowService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String WINDOW_KEY_PREFIX = "flashchat:msg:window:";
    private static final int WINDOW_SIZE = 100;
    private static final long WINDOW_TTL_SECONDS = 24 * 3600L;

    /**
     * per-room 降级标记：roomId -> 降级时间戳
     * 5 分钟后自动恢复（懒检查模式）
     */
    private final ConcurrentHashMap<String, Long> degradedRooms = new ConcurrentHashMap<>();
    private static final long DEGRADE_RECOVER_MS = 5 * 60 * 1000L;

    private String buildKey(String roomId) {
        return WINDOW_KEY_PREFIX + roomId;
    }

    // ==================== 写入 ====================

    /**
     * Pipeline 合并三个命令为一次网络往返（<1ms）
     * <p>
     * ZADD + ZREMRANGEBYRANK（裁剪到 WINDOW_SIZE）+ EXPIRE（刷新 TTL）
     * 失败不阻塞发消息流程，仅标记降级。
     * 成功时主动清除降级标记。
     */
    @Override
    public void addToWindow(String roomId, Long dbId, ChatBroadcastMsgRespDTO msg) {
        String key = buildKey(roomId);
        String json = JSON.toJSONString(msg);

        try {
            stringRedisTemplate.executePipelined(
                    (RedisConnection connection) -> {
                        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                        byte[] memberBytes = json.getBytes(StandardCharsets.UTF_8);

                        // 1. ZADD score=dbId member=json
                        connection.zSetCommands().zAdd(keyBytes, dbId.doubleValue(), memberBytes);

                        // 2. 裁剪：ZREMRANGEBYRANK key 0 -(WINDOW_SIZE+1)
                        //    删除排名最靠前（最旧）的条目，只保留最新 WINDOW_SIZE 条
                        connection.zSetCommands().zRemRange(keyBytes, 0, -(WINDOW_SIZE + 1));

                        // 3. 刷新 TTL
                        connection.keyCommands().expire(keyBytes, WINDOW_TTL_SECONDS);

                        return null;
                    });

            // 写入成功，清除该房间的降级标记（如果有）
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
                        .reverseRangeByScoreWithScores(key,
                                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                                0, pageSize);
            } else {
                // 翻页：score < cursor（用 cursor - 1 模拟开区间，score 是整数所以等价）
                tuples = stringRedisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(key,
                                Double.NEGATIVE_INFINITY,
                                cursor.doubleValue() - 1,
                                0, pageSize);
            }

            // ===== Step 4: 反序列化 =====
            List<ChatBroadcastMsgRespDTO> messages;
            if (tuples == null || tuples.isEmpty()) {
                messages = List.of();
            } else {
                messages = tuples.stream()
                        .map(t -> JSON.parseObject(t.getValue(), ChatBroadcastMsgRespDTO.class))
                        .collect(Collectors.toList());
            }

            return WindowQueryResult.builder()
                    .messages(messages)
                    .windowMinScore(windowMinScore)
                    .build();

        } catch (Exception e) {
            log.warn("[窗口读取失败] room={}, cursor={}, 降级到 DB", roomId, cursor, e);
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
                    .rangeByScore(key,
                            lastAckMsgId.doubleValue() + 1,
                            Double.POSITIVE_INFINITY);

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

    // ==================== 清理 ====================

    @Override
    public void deleteWindow(String roomId) {
        try {
            stringRedisTemplate.delete(buildKey(roomId));
            degradedRooms.remove(roomId);
            log.info("[窗口删除] room={}", roomId);
        } catch (Exception e) {
            log.warn("[窗口删除失败] room={}, TTL 24h 兜底清理", roomId, e);
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
}