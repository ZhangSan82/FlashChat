package com.flashchat.cache;

/**
 * 多级缓存接口
 * <p>
 * 在 DistributedCache（Redis + 分布式锁 + BloomFilter）基础上
 * 增加本地缓存层（Caffeine）
 * <p>
 * 读取链路：本地 Caffeine → Redis → 分布式锁 → DB
 * 写入/删除时同时操作两层缓存，保证一致性
 */
public interface MultistageCache extends DistributedCache {

    /**
     * 仅失效本地缓存
     * <p>
     * 当前单机部署场景：一般不需要单独调用（delete 方法已同时清理两层）
     * 预留给未来多节点场景：收到缓存失效广播时只清本地
     */
    void invalidateLocal(String key);

    /**
     * 本地缓存是否启用
     */
    boolean isLocalCacheEnabled();
}