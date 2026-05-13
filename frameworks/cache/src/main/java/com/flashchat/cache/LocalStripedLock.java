package com.flashchat.cache;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地分片锁。
 * <p>
 * 用于 Redis 熔断 / Redisson 锁竞争失败时的单机回源收敛：
 * 不同 key 经 hash 后落到固定数量的 ReentrantLock 中，同分片内的并发回源被强制串行化，
 * 同分片内的二次查询能直接命中前一个线程刚回填的本地缓存，从而避免「热点 key 多线程同时打 DB」。
 * <p>
 * 为什么不为每个 key 单独建一把锁：
 * 1. 每个 key 一把锁需要 ConcurrentHashMap&lt;String, Lock&gt;，长期运行下锁对象会无限累积，
 *    带来锁表膨胀、GC 压力和生命周期清理负担。
 * 2. 固定分片数量在内存占用上恒定，更适合作为基础框架组件。
 * <p>
 * 设计选择：
 * 1. stripes 必须是 2 的幂，路由用位运算 {@code hash & (length - 1)} 代替取模。
 * 2. 对 {@code hashCode} 做高 16 位异或扰动，降低 key 命名分布偏倚导致的分片倾斜。
 * 3. 不依赖外部库（如 Guava Striped），减少基础组件的依赖面。
 * <p>
 * 注意：
 * 本地分片锁只解决「单机内」并发收敛，多节点部署时仍需要 Redisson 等分布式锁负责跨节点互斥。
 * 在当前框架中，Redisson 锁是主路径，本地分片锁是 Redis 异常 / 锁竞争失败后的兜底。
 */
final class LocalStripedLock {

    /**
     * 锁数组。
     * 数量在构造时固定，避免运行期 resize 导致的同步开销。
     */
    private final ReentrantLock[] locks;

    /**
     * @param stripes 分片数，必须是 2 的幂；典型值 128 / 256 / 512
     */
    LocalStripedLock(int stripes) {
        // 强制 2 的幂，使得后续可以用 (length - 1) 的位掩码代替取模运算
        if (Integer.bitCount(stripes) != 1) {
            throw new IllegalArgumentException("stripes must be power of two");
        }
        this.locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            this.locks[i] = new ReentrantLock();
        }
    }

    /**
     * 根据 key 路由到对应分片锁。
     * <p>
     * 步骤：
     * 1. 取 {@code key.hashCode()}，null key 兜底为 0
     * 2. 对高 16 位做异或扰动（与 ConcurrentHashMap 同样的做法），缓解低位冲突导致的分片倾斜
     * 3. 与 length-1 做位与，等价于对长度取模
     *
     * @param key 缓存键
     * @return 对应分片上的 ReentrantLock
     */
    ReentrantLock getLock(String key) {
        int hash = key == null ? 0 : key.hashCode();
        // 高位扰动：很多业务 key 形如 "flashchat_account_123456",
        // 低位差异较大但高位完全一致,直接位与会导致部分分片热度过高。
        hash ^= hash >>> 16;
        return locks[hash & (locks.length - 1)];
    }
}
