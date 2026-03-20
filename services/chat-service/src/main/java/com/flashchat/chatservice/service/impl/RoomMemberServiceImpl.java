package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.DistributedCache;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.mapper.RoomMemberMapper;
import com.flashchat.chatservice.service.RoomMemberService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class RoomMemberServiceImpl extends ServiceImpl< RoomMemberMapper,RoomMemberDO> implements RoomMemberService {

    private final DistributedCache distributedCache;
    private static final long CACHE_TIMEOUT = 60000L;


    /**
     * 通过缓存查询用户
     */
    @Override
    public RoomMemberDO getRoomMemberByRoomIdAndMemberId(String roomId, Long memberId) {
        RoomMemberDO roomMemberDO = distributedCache.safeGet(buildCacheKey(roomId, memberId),
                RoomMemberDO.class,
                ()->this.lambdaQuery()
                        .eq(RoomMemberDO::getRoomId,roomId)
                        .eq(RoomMemberDO::getMemberId,memberId)
                        .one(),
                CACHE_TIMEOUT
                );
        return roomMemberDO;

    }

    /**
     * 保存 + 写入缓存
     */
    @Override
    public boolean saveWithCache(RoomMemberDO entity) {
        boolean result = this.save(entity);
        if (result) {
            Long memberId = extractMemberId(entity);
            if (entity.getRoomId() != null && memberId != null) {
                distributedCache.put(
                        buildCacheKey(entity.getRoomId(), memberId),
                        entity,
                        CACHE_TIMEOUT
                );
            }
        }
        return result;
    }


    /**
     * 更新 + 自动失效缓存
     * 替代所有直接调用 updateById 的地方
     */
    @Override
    public boolean updateWithCacheEvict(RoomMemberDO entity) {
        boolean result = this.updateById(entity);
        if (result) {
            Long memberId = extractMemberId(entity);
            if (entity.getRoomId() != null && memberId != null) {
                evictCache(entity.getRoomId(), memberId);
                log.debug("[缓存失效] roomMember room={}, memberId={}", entity.getRoomId(), memberId);
            }
        }
        return result;
    }

    /**
     * 手动失效缓存（用于 lambdaUpdate 等无法走 updateWithCacheEvict 的场景）
     */
    @Override
    public void evictCache(String roomId, Long memberId) {
        try {
            distributedCache.delete(buildCacheKey(roomId, memberId));
        } catch (Exception e) {
            log.error("[缓存失效异常] room={}, memberId={}", roomId, memberId, e);
        }
    }

    /**
     * 批量失效某个房间所有成员的缓存（关闭房间时使用）
     */
    @Override
    public void evictAllMemberCacheInRoom(String roomId) {
        // 先查出所有成员（不限状态，因为 LEFT/KICKED 的也可能在缓存里）
        List<RoomMemberDO> members = this.lambdaQuery()
                .eq(RoomMemberDO::getRoomId, roomId)
                .list();

        if (members == null || members.isEmpty()) {
            return;
        }

        int count = 0;
        for (RoomMemberDO member : members) {
            Long memberId = extractMemberId(member);
            if (memberId != null) {
                evictCache(roomId, memberId);
                count++;
            }
        }
        log.info("[批量缓存失效] room={}, 清理 {} 个成员缓存", roomId, count);
    }




    private String buildCacheKey(String roomId, Long memberId) {
        return CacheUtil.buildKey("flashchat", "roomMember", roomId, String.valueOf(memberId));
    }

    /**
     * 从实体中提取 memberId（兼容匿名成员和注册用户）
     */
    private Long extractMemberId(RoomMemberDO entity) {
        return entity.getMemberId() != null ? entity.getMemberId() : entity.getUserId();
    }
}
