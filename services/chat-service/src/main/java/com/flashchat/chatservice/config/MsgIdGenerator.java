package com.flashchat.chatservice.config;

import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.convention.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息顺序 ID 生成器
 * 解决的核心矛盾：
 *   异步写 DB 时广播消息没有 dbId → ACK / 游标分页 / 离线消息全断
 * 三层保障：
 *   正常路径 → Redis INCR 预分配 ID + 异步攒批写 DB（高吞吐）
 *   重启路径 → Lua 脚本 + DB 水位恢复（防 ID 回退）
 *   降级路径 → lastKnownId 本地递增 + 同步写 DB（保可用性）
 * 关键设计决策：
 *   1. 全局单 key（flashchat:msg_seq:global），因为 t_message 是单表全局主键
 *   2. 降级不用 MySQL 自增（存在 ID 空间冲突），用 AtomicLong lastKnownId
 *   3. Lua 脚本原子执行 EXISTS + SET + INCR，消除并发初始化窗口
 */
@Slf4j
@Component
public class MsgIdGenerator {

    /**
     * 全局唯一的 Redis Key
     */
    private static final String REDIS_KEY = "flashchat:msg_seq:global";

    /**
     * 批量段大小:一次 INCRBY 拉多少 ID 回本地。
     * 100 在吞吐与崩溃空洞之间取平衡:
     *   - 吞吐:99% 的 tryNextId 变成本地 CAS,ns 级;
     *   - 空洞:单进程重启最多丢弃 99 个 ID(容忍,msgSeqId 已声明可空洞)。
     */
    private static final int SEGMENT_SIZE = 100;

    /**
     * Lua 脚本:原子执行「不存在则初始化 + INCRBY N」
     * 返回该批次分配的最大 ID (含),调用方据此推导 [max-N+1, max] 区间。
     * KEYS[1] = flashchat:msg_seq:global
     * ARGV[1] = DB 水位值 (SELECT MAX(id) 的结果)
     * ARGV[2] = 段大小 N
     */
    private static final DefaultRedisScript<Long> INIT_AND_INCRBY;

    static {
        INIT_AND_INCRBY = new DefaultRedisScript<>();
        INIT_AND_INCRBY.setScriptText(
                "if redis.call('EXISTS', KEYS[1]) == 0 then " +
                        "  redis.call('SET', KEYS[1], ARGV[1]) " +
                        "end " +
                        "return redis.call('INCRBY', KEYS[1], ARGV[2])"
        );
        INIT_AND_INCRBY.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final MessageMapper messageMapper;

    /**
     * 本地 ID 段状态,由 segmentLock 保护。
     * segmentNext == segmentMax + 1 表示段已耗尽,需要重新拉段。
     * volatile 让无锁快路径可以读到最新写入 (不保证原子性,快路径内部用 CAS 兜底)。
     */
    private volatile long segmentNext = 1L;
    private volatile long segmentMax = 0L;
    private final Object segmentLock = new Object();
    /**
     * 降级用的本地计数器
     * 每次 Redis INCR 成功都更新此值
     * Redis 不可用时从此值继续递增，保证 ID 一定大于所有已分配 ID
     *
     * 为什么不用 MySQL 自增做降级：
     *   Redis 预分配的 ID 可能超前于 DB 实际写入（攒批还没 flush）
     *   此时 MySQL 自增指针落后于 Redis，降级分配的 ID 会和攒批队列中的 ID 冲突
     */
    private final AtomicLong lastKnownId = new AtomicLong(0);
    private final AtomicBoolean trustedRedisFloorReady = new AtomicBoolean(false);

    public MsgIdGenerator(StringRedisTemplate stringRedisTemplate,
                          MessageMapper messageMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.messageMapper = messageMapper;
    }

    @PostConstruct
    public void preloadDbFloor() {
        try {
            Long maxId = messageMapper.selectMaxId();
            long floor = (maxId != null) ? maxId : 0L;
            lastKnownId.updateAndGet(current -> Math.max(current, floor));
            log.info("[消息ID-预热] DB水位={}", floor);
        } catch (Exception e) {
            log.warn("[消息ID-预热失败] 无法加载 DB 水位，当前仅允许 Redis 正常路径分配", e);
        }
    }

    /**
     * 尝试获取下一个消息顺序 ID
     * 性能模型:
     *   本地维护 [segmentNext, segmentMax] 段,消费时 segmentNext++,零 Redis 调用。
     *   段耗尽时一次性 INCRBY SEGMENT_SIZE 拉下一段,99% 的调用走本地内存路径。
     *   同房间临界区(sendMsg synchronized 块)天然是 tryNextId 的串行域,
     *   段状态由外层锁已经天然互斥,无需额外 segmentLock——但跨房间调用
     *   (房间 hash 到不同 stripe)会并发进入本方法,因此仍需 segmentLock 兜底。
     * 空洞说明:
     *   msgSeqId 单调递增但不保证连续。进程重启会丢弃当前段未消费部分
     *   (最多 SEGMENT_SIZE - 1),XADD/saveSync 双失败也会产生单点空洞。
     *   上游不得依赖 ID 连续性。
     * 可靠性说明:
     *   拉段 Lua 脚本一次 RTT 完成 INIT + INCRBY,段内消费零 RTT。
     *   拉段失败返回 null,调用方走 fallbackNextId(本地 lastKnownId 递增)。
     * @return 正数 ID(成功),或 null(Redis 异常,调用方走 fallbackNextId)
     */
    public Long tryNextId() {
        // ===== 快速路径:本地段未耗尽,读一次 volatile + 进锁再确认 =====
        if (segmentNext <= segmentMax) {
            synchronized (segmentLock) {
                if (segmentNext <= segmentMax) {
                    long id = segmentNext++;
                    final long allocated = id;
                    lastKnownId.updateAndGet(prev -> Math.max(prev, allocated));
                    return id;
                }
            }
        }
        // ===== 慢路径:段耗尽,拉下一段 =====
        return fetchNextSegment();
    }

    private Long fetchNextSegment() {
        synchronized (segmentLock) {
            // double-check:可能已经被其他线程拉过段
            if (segmentNext <= segmentMax) {
                long id = segmentNext++;
                final long allocated = id;
                lastKnownId.updateAndGet(prev -> Math.max(prev, allocated));
                return id;
            }
            try {
                long current = lastKnownId.get();
                if (current == 0L) {
                    // 启动期 preload 失败过,运行期补查一次 DB 水位
                    Long maxId = messageMapper.selectMaxId();
                    current = (maxId != null) ? maxId : 0L;
                    final long recovered = current;
                    lastKnownId.updateAndGet(prev -> Math.max(prev, recovered));
                    log.info("[消息ID-运行期补查水位] floor={}", recovered);
                }
                final long floor = current;

                Long segmentMaxId = stringRedisTemplate.execute(
                        INIT_AND_INCRBY,
                        List.of(REDIS_KEY),
                        String.valueOf(floor),
                        String.valueOf(SEGMENT_SIZE)
                );
                if (segmentMaxId == null) {
                    return null;
                }
                // 拉到的段为 [segmentMaxId - SEGMENT_SIZE + 1, segmentMaxId]
                long newMax = segmentMaxId;
                long newNext = newMax - SEGMENT_SIZE + 1;
                // 防御 floor 与 Redis key 不一致:newNext 必须大于已分配上界
                long knownFloor = lastKnownId.get();
                if (newNext <= knownFloor) {
                    newNext = knownFloor + 1;
                }
                segmentMax = newMax;
                segmentNext = newNext + 1; // 本次消费首个,下次从 next+1 开始
                long id = newNext;

                final long allocated = Math.max(id, newMax);
                lastKnownId.updateAndGet(prev -> Math.max(prev, allocated));
                trustedRedisFloorReady.set(true);

                log.debug("[消息ID-拉段] range=[{}, {}], issue={}", newNext, newMax, id);
                return id;
            } catch (Exception e) {
                log.error("[消息ID-Redis异常] 当前不进入冷启动本地递增降级", e);
                return null;
            }
        }
    }

    /**
     * 降级路径：Redis 不可用时，从本地 lastKnownId 继续递增
     * 保证：降级 ID 一定大于所有已通过 Redis 分配的 ID
     * 原因：lastKnownId 在每次 Redis INCR 成功时都会更新为最大值
     * 注意：冷启动且尚未建立可信水位时，仍然 fail-closed 拒绝盲发本地 ID
     * @return 一定大于之前所有 ID 的正数
     */
    public Long fallbackNextId() {
        if (!trustedRedisFloorReady.get()) {
            throw new ServiceException("消息序号服务未就绪，请稍后重试");
        }
        return lastKnownId.incrementAndGet();
    }
}
