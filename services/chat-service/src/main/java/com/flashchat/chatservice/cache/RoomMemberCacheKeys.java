package com.flashchat.chatservice.cache;

import com.flashchat.cache.toolkit.CacheUtil;

/**
 * RoomMember 缓存 key 统一生成。
 * 本地缓存路由规则依赖 flashchat_roomMember_ 前缀把 key 路由到 roomMemberCache 域。
 */
public final class RoomMemberCacheKeys {

    private static final String ROOT = "flashchat";
    private static final String ROOM_MEMBER = "roomMember";

    private RoomMemberCacheKeys() {
    }

    /** 单成员点查缓存 key：flashchat_roomMember_{roomId}_{accountId} */
    public static String member(String roomId, Long accountId) {
        return CacheUtil.buildKey(ROOT, ROOM_MEMBER, roomId, String.valueOf(accountId));
    }
}
