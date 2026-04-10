package com.flashchat.chatservice.service.dispatch;

import org.springframework.stereotype.Component;

/**
 * 房间级串行锁(striped lock)
 * 用途:
 *   保证同一 roomId 在"分配 msgSeqId → durable handoff → 提交 mailbox"这段临界区内
 *   顺序执行,消除以下竞态:
 *     线程 A 拿到 msgSeqId=10 → 被 OS 调度打断
 *     线程 B 拿到 msgSeqId=11 → 先完成 mailbox.submit
 *     线程 A 后 submit
 *   → mailbox 中同一房间执行顺序 11 在前,广播顺序倒置,窗口 ZADD 顺序也反了。
 * 设计说明:
 *   - 使用固定大小的 stripes 数组,避免为每个 roomId 分配 Lock 造成泄漏
 *   - stripes 取素数 64,配合 floorMod(hashCode, 64) 得到较均匀分布
 *   - 返回 Object 让调用方直接 synchronized,零额外包装成本
 *   - 临界区内包含 XADD(一次 Redis RTT),同房间吞吐约 1000 msg/s,足够单机场景
 */
@Component
public class RoomSerialLock {

    private static final int STRIPES = 64;

    private final Object[] locks;

    public RoomSerialLock() {
        this.locks = new Object[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.locks[i] = new Object();
        }
    }

    public Object lockFor(String roomId) {
        return locks[Math.floorMod(roomId.hashCode(), STRIPES)];
    }
}
