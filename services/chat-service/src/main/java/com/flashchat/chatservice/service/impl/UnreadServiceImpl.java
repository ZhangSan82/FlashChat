package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.service.UnreadService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UnreadServiceImpl implements UnreadService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RoomChannelManager roomChannelManager;
    private final RoomMemberService roomMemberService;
    private final MessageMapper messageMapper;

    private static final String UNREAD_KEY_PREFIX = "flashchat:unread:";
    /** Key 过期时间（小时），防止僵尸数据；弱一致提醒不需要长期保留 */
    private static final long KEY_EXPIRE_HOURS = 24;

    private String getKey(Long accountId) {
        return UNREAD_KEY_PREFIX + accountId;
    }

    // ================================================================
    //                          写操作
    // ================================================================

    /**
     * 发消息后，批量给房间成员未读数 +1
     *
     * 使用 Redis Pipeline：
     *   50 个 HINCRBY 命令 → 1 次网络往返 → <1ms
     */
    @Override
    public void incrementUnread(String roomId, Long excludeMemberId) {
        Set<Long> memberIds = roomChannelManager.getRoomMemberIds(roomId);
        if (memberIds.isEmpty()) return;

        try {
            long expireSeconds = KEY_EXPIRE_HOURS * 3600;
            // Pipeline 批量执行：HINCRBY + EXPIRE 续期，同一 RTT 内完成
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long uid : memberIds) {
                    if (uid.equals(excludeMemberId)) continue;

                    byte[] key = getKey(uid).getBytes();
                    byte[] field = roomId.getBytes();
                    connection.hashCommands().hIncrBy(key, field, 1);
                    connection.commands().expire(key, expireSeconds);
                }
                return null;
            });

            log.debug("[未读+1] room={}, 影响 {} 人", roomId, memberIds.size() - 1);
        } catch (Exception e) {
            // Redis 故障不影响发消息
            log.error("[未读+1失败] room={}", roomId, e);
        }
    }

    /**
     * ACK 后清零
     */
    @Override
    public void clearUnread(Long accountId, String roomId) {
        try {
            stringRedisTemplate.opsForHash().delete(getKey(accountId), roomId);
            log.debug("[未读清零] memberId={}, room={}", accountId, roomId);
        } catch (Exception e) {
            log.error("[未读清零失败] memberId={}, room={}", accountId, roomId, e);
        }
    }

    /**
     * 离开房间 / 被踢出
     */
    @Override
    public void removeRoomUnread(Long accountId, String roomId) {
        clearUnread(accountId, roomId);
    }

    /**
     * 房间关闭，批量清除所有成员（从内存获取成员列表）
     * 新增 DB 兜底，防止内存已被 closeRoom 清空的情况
     */
    @Override
    public void clearRoomForAllMembers(String roomId) {
        Set<Long> memberIds = roomChannelManager.getRoomMemberIds(roomId);
        if (memberIds.isEmpty()) {
            // 兜底：内存已清空（可能 closeRoom 先执行了），从 DB 查
            log.warn("[清未读-兜底] room={}, 内存无成员数据，从DB查询", roomId);
            memberIds = queryMemberIdsFromDB(roomId);
        }
        clearRoomForAllMembers(roomId, memberIds);
    }

    /**
     *接收调用方传入的成员 ID 快照，不依赖内存状态
     */
    @Override
    public void clearRoomForAllMembers(String roomId, Set<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) return;

        try {
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long uid : memberIds) {
                    byte[] key = getKey(uid).getBytes();
                    byte[] field = roomId.getBytes();
                    connection.hashCommands().hDel(key, field);
                }
                return null;
            });
            log.info("[房间关闭-清未读] room={}, 清理 {} 人", roomId, memberIds.size());
        } catch (Exception e) {
            log.error("[房间关闭-清未读失败] room={}", roomId, e);
        }
    }

    /**
     * 从 DB 查询房间所有成员 ID（兜底方法）
     * <p>
     * 不限制 status 条件：因为调用时成员状态可能已被 doCloseRoom 批量更新为 LEFT，
     * 如果加 ACTIVE 条件会查不到任何人。
     * LEFT/KICKED 成员执行 HDEL 时如果 Redis 中没有对应 field，HDEL 是幂等的，无副作用。
     */
    private Set<Long> queryMemberIdsFromDB(String roomId) {
        try {
            List<RoomMemberDO> members = roomMemberService.lambdaQuery()
                    .eq(RoomMemberDO::getRoomId, roomId)
                    .select(RoomMemberDO::getAccountId)
                    .list();
            return members.stream()
                    .map(RoomMemberDO::getAccountId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("[查成员ID失败] room={}", roomId, e);
            return Set.of();
        }
    }

    // ================================================================
    //                          读操作
    // ================================================================

    /**
     * 获取用户所有房间的未读数
     *
     * 路径：
     *   正常：Redis HGETALL → <1ms
     *   兜底：Redis 无数据 → DB COUNT → 回写 Redis
     */
    @Override
    public Map<String, Integer> getAllUnreadCounts(Long accountId) {
        // 1. 先查 Redis
        Map<String, Integer> redisResult = getFromRedis(accountId);
        if (redisResult != null) {
            redisResult.replaceAll((roomId,count)->
                    Math.min(count,MAX_UNREAD_DISPLAY));
            return redisResult;
        }

        // 2. Redis 无数据 → DB 兜底
        log.info("[未读-DB兜底] memberId={}", accountId);
        Map<String, Integer> dbResult = computeAllFromDB(accountId);

        // 3. 回写 Redis
        writeBackToRedis(accountId, dbResult);

        return dbResult;
    }

    /**
     * 获取单个房间的未读数
     */
    @Override
    public int getUnreadCount(Long accountId, String roomId) {
        try {
            Object val = stringRedisTemplate.opsForHash().get(getKey(accountId), roomId);
            if (val != null) {
                return Math.min(Math.max(0, Integer.parseInt(val.toString())), MAX_UNREAD_DISPLAY);
            }
        } catch (Exception e) {
            log.error("[查单房间未读失败] memberId={}, room={}", accountId, roomId, e);
        }

        // Redis 没有 → DB 兜底
        return computeOneFromDB(accountId, roomId);
    }

    // ================================================================
    //                       Redis 读写
    // ================================================================

    /**
     * 从 Redis 读取所有未读数
     * @return null 表示 key 不存在（需要 DB 兜底）
     */
    private Map<String, Integer> getFromRedis(Long accountId) {
        try {
            String key = getKey(accountId);
            Boolean exists = stringRedisTemplate.hasKey(key);
            if (exists == null || !exists) {
                return null;
            }

            Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
            Map<String, Integer> result = new HashMap<>();
            raw.forEach((k, v) -> {
                String field = k.toString();
                // 跳过初始化标记
                if ("__init__".equals(field)) return;

                int count = Integer.parseInt(v.toString());
                if (count > 0) {
                    result.put(field, count);
                }
            });
            return result;
        } catch (Exception e) {
            log.error("[Redis读未读失败] memberId={}", accountId, e);
            return null;
        }
    }

    /**
     * 将 DB 计算结果回写 Redis
     */
    private void writeBackToRedis(Long accountId, Map<String, Integer> unreadMap) {
        try {
            String key = getKey(accountId);
            Map<String, String> writeMap = new HashMap<>();

            if (unreadMap.isEmpty()) {
                // 写一个标记，防止反复 DB 兜底
                writeMap.put("__init__", "0");
            } else {
                unreadMap.forEach((roomId, count) ->
                        writeMap.put(roomId, count.toString()));
            }

            stringRedisTemplate.opsForHash().putAll(key, writeMap);
            stringRedisTemplate.expire(key, KEY_EXPIRE_HOURS, TimeUnit.HOURS);

            log.debug("[未读回写Redis] memberId={}, rooms={}", accountId, unreadMap.size());
        } catch (Exception e) {
            log.error("[未读回写Redis失败] memberId={}", accountId, e);
        }
    }

    // ================================================================
    //                       DB 兜底计算
    // ================================================================

    /**
     * 从 DB 计算所有房间的未读数（单条聚合 SQL，消除 N+1）
     */
    private Map<String, Integer> computeAllFromDB(Long accountId) {
        List<Map<String, Object>> rows = messageMapper.selectBatchUnreadCounts(accountId);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String roomId = row.get("room_id").toString();
            int count = Math.min(((Number) row.get("cnt")).intValue(), MAX_UNREAD_DISPLAY);
            result.put(roomId, count);
        }
        return result;
    }

    /**
     * 从 DB 计算单个房间的未读数
     */
    private int computeOneFromDB(Long accountId, String roomId) {
        RoomMemberDO rm = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, accountId);

        if (rm == null || rm.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) return 0;
        return doCount(roomId, rm.getLastAckMsgId());
    }

    /**
     * 实际 COUNT 查询
     * 索引命中：idx_room_id (room_id, id)
     */
    private int doCount(String roomId, Long lastAckMsgId) {
        long ackId = lastAckMsgId != null ? lastAckMsgId : 0L;
        List<Long> ids = messageMapper.selectUnreadMsgIds(roomId, ackId);
        int count = ids != null ? ids.size() : 0;
        return Math.min(count, MAX_UNREAD_DISPLAY);
    }
}