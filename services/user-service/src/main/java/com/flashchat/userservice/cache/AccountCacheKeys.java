package com.flashchat.userservice.cache;

import com.flashchat.cache.toolkit.CacheUtil;

/**
 * Account 缓存 key 统一生成。
 * 本地缓存路由规则依赖 flashchat_account_ 前缀把 key 路由到 accountCache 域。
 */
public final class AccountCacheKeys {

    private static final String ROOT = "flashchat";
    private static final String ACCOUNT = "account";

    private AccountCacheKeys() {
    }

    /** 按业务账号 ID 缓存 key：flashchat_account_{accountId} */
    public static String byAccountId(String accountId) {
        return CacheUtil.buildKey(ROOT, ACCOUNT, accountId);
    }

    /** 按数据库主键缓存 key：flashchat_account_id_{dbId} */
    public static String byDbId(Long id) {
        return CacheUtil.buildKey(ROOT, ACCOUNT, "id", String.valueOf(id));
    }
}
