package com.flashchat.chatservice.cache;

import com.flashchat.cache.core.CacheGetIfAbsent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Room 缓存未命中且 DB 为空时的后置动作。
 * 只做轻量日志/统计，不做重逻辑。
 */
@Component
@Slf4j
public class RoomCacheIfAbsentHandler implements CacheGetIfAbsent<String> {

    @Override
    public void execute(String roomKey) {
        log.debug("[Room 缓存未命中且 DB 为空] key={}", roomKey);
    }
}
