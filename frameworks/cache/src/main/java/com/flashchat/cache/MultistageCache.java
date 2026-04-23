package com.flashchat.cache;

/**
 * 多级缓存接口
 * <p>
 * 在 DistributedCache（Redis + 分布式锁 + BloomFilter）基础上
 * 增加本地缓存层（Caffeine）+ Redis 熔断器
 * <p>
 * 读取链路：本地 Caffeine → 熔断器检查 → Redis → 分布式锁 → DB
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

    /**
     * 获取 Redis 熔断器当前状态
     * <p>
     * 返回值：CLOSED / OPEN / HALF_OPEN。
     * 主要用途：
     * 1. Actuator endpoint 暴露
     * 2. 运维面板展示当前 Redis 保护状态
     * 3. 故障排查时快速判断缓存链路是否正在熔断
     */
    String getCircuitBreakerState();
}
