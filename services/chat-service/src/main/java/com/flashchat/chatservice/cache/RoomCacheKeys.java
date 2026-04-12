package com.flashchat.chatservice.cache;

import com.flashchat.cache.toolkit.CacheUtil;

/**
 * Room 缓存 key 统一生成，避免业务层到处手搓 key 造成前缀不一致。
 * 本地缓存路由规则依赖 flashchat_room_ 前缀把 key 路由到 roomCache 域。
 */
public final class RoomCacheKeys {

    private static final String ROOT = "flashchat";
    private static final String ROOM = "room";
    private static final String ROOM_CLOSED = "roomClosed";
    private static final String ROOM_PUBLIC = "roomPublic";

    private RoomCacheKeys() {
    }

    /** 单房间对象缓存 key：flashchat_room_{roomId} */
    public static String room(String roomId) {
        return CacheUtil.buildKey(ROOT, ROOM, roomId);
    }

    /** 已关闭房间 marker key：flashchat_roomClosed_{roomId} */
    public static String closedMarker(String roomId) {
        return CacheUtil.buildKey(ROOT, ROOM_CLOSED, roomId);
    }

    /** 公开房间列表结果缓存 key */
    public static String publicList(int page, int size, String sort) {
        return CacheUtil.buildKey(ROOT, ROOM_PUBLIC,
                "page", String.valueOf(page),
                "size", String.valueOf(size),
                "sort", sort);
    }

    /** 公开房间列表 key 前缀，用于批量失效 */
    public static String publicListPrefix() {
        return ROOT + "_" + ROOM_PUBLIC + "_*";
    }
}
