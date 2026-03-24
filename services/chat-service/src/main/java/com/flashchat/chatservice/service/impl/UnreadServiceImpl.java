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

@Service
@Slf4j
@RequiredArgsConstructor
public class UnreadServiceImpl implements UnreadService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RoomChannelManager roomChannelManager;
    private final RoomMemberService roomMemberService;
    private final MessageMapper messageMapper;

    private static final String UNREAD_KEY_PREFIX = "flashchat:unread:";
    /** Key 过期时间（小时），防止僵尸数据 */
    private static final long KEY_EXPIRE_HOURS = 72;

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
            // Pipeline 批量执行
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long uid : memberIds) {
                    if (uid.equals(excludeMemberId)) continue;

                    byte[] key = getKey(uid).getBytes();
                    byte[] field = roomId.getBytes();
                    connection.hashCommands().hIncrBy(key, field, 1);
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
     * 房间关闭，批量清除所有成员
     */
    @Override
    public void clearRoomForAllMembers(String roomId) {
        Set<Long> memberIds = roomChannelManager.getRoomMemberIds(roomId);
        if (memberIds.isEmpty()) return;

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
                return Math.max(0, Integer.parseInt(val.toString()));
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
     * 从 DB 计算所有房间的未读数
     */
    private Map<String, Integer> computeAllFromDB(Long accountId) {
        List<RoomMemberDO> activeMembers = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getAccountId, accountId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .list();

        if (activeMembers == null || activeMembers.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> result = new HashMap<>();
        for (RoomMemberDO rm : activeMembers) {
            int count = doCount(rm.getRoomId(), rm.getLastAckMsgId());
            if (count > 0) {
                result.put(rm.getRoomId(), count);
            }
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
        Long count = messageMapper.selectCount(
                new LambdaQueryWrapper<MessageDO>()
                        .eq(MessageDO::getRoomId, roomId)
                        .gt(MessageDO::getId, ackId)
                        .eq(MessageDO::getStatus, 0)
        );
        return count != null ? count.intValue() : 0;
    }
}