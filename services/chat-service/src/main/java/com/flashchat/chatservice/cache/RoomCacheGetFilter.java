package com.flashchat.chatservice.cache;

import com.flashchat.cache.core.CacheGetFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Room 缓存过滤器：拦截已关闭/已删除的房间。
 * <p>
 * 解决 BloomFilter 无法删除的短板：房间关闭后写一个短 TTL 的 closed marker，
 * 之后凡是单体读取 Room，都先看 marker 是否存在，存在则直接返回 null，不再回源。
 */
@Component
@RequiredArgsConstructor
public class RoomCacheGetFilter implements CacheGetFilter<String> {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean filter(String roomKey) {
        String roomId = roomKey.substring(roomKey.lastIndexOf('_') + 1);
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(RoomCacheKeys.closedMarker(roomId))
        );
    }
}
