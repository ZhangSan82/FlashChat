package com.flashchat.cache.config;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地缓存管理器
 * <p>
 * 封装三个业务域的 Caffeine 实例，根据 key 前缀自动路由
 * 前缀映射：flashchat_room_ → roomCache，flashchat_roomMember_ → roomMemberCache，
 * flashchat_account_ → accountCache，其他前缀不走本地缓存
 */
@Slf4j
public class LocalCacheManager {

    /**
     * 空值标记 — 标记"DB 中确认不存在的 key"
     * 使用独立类型，调试时可读性更好
     */
    private static final class NullValueMarker {

        private final boolean degraded;

        private NullValueMarker(boolean degraded) {
            this.degraded = degraded;
        }

        @Override
        public String toString() {
            return degraded ? "LocalCache.DEGRADED_NULL_VALUE" : "LocalCache.NULL_VALUE";
        }
    }

    private static final class DegradedValueMarker {

        private final Object value;

        private DegradedValueMarker(Object value) {
            this.value = value;
        }
    }

    public static final Object NULL_VALUE = new NullValueMarker(false);
    private static final Object DEGRADED_NULL_VALUE = new NullValueMarker(true);

    // ==================== key 前缀常量 ====================
    // 注意：roomMember 前缀比 room 更长，routeCache 中必须先匹配 roomMember

    private static final String ROOM_PREFIX = "flashchat_room_";
    private static final String ROOM_MEMBER_PREFIX = "flashchat_roomMember_";
    private static final String ACCOUNT_PREFIX = "flashchat_account_";

    // ==================== Caffeine 实例 ====================

    private final Cache<String, Object> roomCache;
    private final Cache<String, Object> roomMemberCache;
    private final Cache<String, Object> accountCache;
    private final boolean available;

    public LocalCacheManager(Cache<String, Object> roomCache,
                             Cache<String, Object> roomMemberCache,
                             Cache<String, Object> accountCache,
                             RedisDistributedProperties.LocalCacheProperties localProps) {
        this.roomCache = roomCache;
        this.roomMemberCache = roomMemberCache;
        this.accountCache = accountCache;
        this.available = localProps.isEnabled()
                && (roomCache != null || roomMemberCache != null || accountCache != null);
    }

    // ==================== 读操作 ====================

    /**
     * 从本地缓存获取值
     *
     * @return 业务对象 / NULL_VALUE 标记 / null（未命中）
     */
    public Object get(String key) {
        if (!available) {
            return null;
        }
        Cache<String, Object> cache = routeCache(key);
        Object value = cache != null ? cache.getIfPresent(key) : null;
        return unwrapValue(value);
    }

    // ==================== 写操作 ====================

    /**
     * 写入本地缓存（有效值）
     * <p>
     * 直接存储原始引用，不做深拷贝。
     * 当前所有 safeGet 调用方对返回值都是只读使用，风险可控。
     * 即使被意外修改，域 TTL（15-45 秒）后自动恢复。
     */
    public void put(String key, Object value) {
        if (!available || value == null) {
            return;
        }
        Cache<String, Object> cache = routeCache(key);
        if (cache != null) {
            cache.put(key, value);
        }
    }

    /**
     * 写入空值标记
     * 通过 Caffeine Expiry API，NullValue 使用独立的更短 TTL
     */
    public void putNullValue(String key) {
        if (!available) {
            return;
        }
        Cache<String, Object> cache = routeCache(key);
        if (cache != null) {
            cache.put(key, NULL_VALUE);
        }
    }

    /**
     * 写入 Redis 异常期间的本地降级值。
     * <p>
     * 降级值使用更短 TTL，只用于挡住短时间热点请求，不承担长期一致性职责。
     */
    public void putDegradedValue(String key, Object value) {
        if (!available) {
            return;
        }
        Cache<String, Object> cache = routeCache(key);
        if (cache != null) {
            cache.put(key, value == null ? DEGRADED_NULL_VALUE : new DegradedValueMarker(value));
        }
    }

    // ==================== 删除操作 ====================

    /**
     * 失效本地缓存
     * 写操作（更新/删除 DB）后必须调用
     */
    public void invalidate(String key) {
        if (!available) {
            return;
        }
        Cache<String, Object> cache = routeCache(key);
        if (cache != null) {
            cache.invalidate(key);
        }
    }

    // ==================== 判断方法 ====================

    public static boolean isNullValue(Object value) {
        return value instanceof NullValueMarker;
    }

    public static boolean isDegradedValue(Object value) {
        return value instanceof DegradedValueMarker
                || (value instanceof NullValueMarker marker && marker.degraded);
    }

    private static Object unwrapValue(Object value) {
        if (value instanceof DegradedValueMarker marker) {
            return marker.value;
        }
        return value;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean supportsKey(String key) {
        return available && routeCache(key) != null;
    }

    // ==================== 路由逻辑 ====================

    /**
     * 根据 key 前缀路由到对应的 Caffeine 实例
     * roomMember 前缀比 room 更长，必须先匹配
     */
    private Cache<String, Object> routeCache(String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith(ROOM_MEMBER_PREFIX)) {
            return roomMemberCache;
        }
        if (key.startsWith(ROOM_PREFIX)) {
            return roomCache;
        }
        if (key.startsWith(ACCOUNT_PREFIX)) {
            return accountCache;
        }
        return null;
    }
}
