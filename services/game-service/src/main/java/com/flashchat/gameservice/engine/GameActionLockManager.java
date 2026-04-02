package com.flashchat.gameservice.engine;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 游戏写操作局部锁管理器。
 * <p>
 * 作用：
 * 1. 让同一个 roomId / gameId 的低频写操作串行执行
 * 2. 在事务提交完成前持续持有锁，避免 DB 与内存态短暂不一致时被后续请求穿透
 * 3. 用引用计数安全回收锁对象，避免极端并发下出现锁对象 ABA 问题
 */
@Component
public class GameActionLockManager {

    /**
     * 业务键 -> 锁持有者。
     * <p>
     * 业务键格式约定：
     * room:{roomId}
     * game:{gameId}
     */
    private final ConcurrentHashMap<String, LockHolder> localLocks = new ConcurrentHashMap<>();

    /**
     * 获取并加锁。
     * <p>
     * 调用方必须在 finally 或 try-with-resources 中关闭返回的句柄。
     */
    public LockHandle lock(String key) {
        LockHolder holder = localLocks.compute(key, (ignored, existing) -> {
            LockHolder current = existing == null ? new LockHolder() : existing;
            current.retain();
            return current;
        });
        holder.lock();
        return new LockHandle(key, holder);
    }

    private void releaseInternal(String key, LockHolder holder) {
        try {
            holder.unlock();
        } finally {
            localLocks.compute(key, (ignored, existing) -> {
                if (existing != holder) {
                    return existing;
                }
                return existing.release() == 0 ? null : existing;
            });
        }
    }

    /**
     * 锁句柄。
     * <p>
     * 默认在 close 时解锁；如果当前处于事务中，可调用
     * {@link #deferUnlockAfterTransaction()} 将解锁延后到事务完成后。
     */
    public final class LockHandle implements AutoCloseable {

        private final String key;
        private final LockHolder holder;

        private boolean unlockDeferred;
        private boolean closed;

        private LockHandle(String key, LockHolder holder) {
            this.key = key;
            this.holder = holder;
        }

        /**
         * 将解锁动作延后到事务完成后执行。
         * <p>
         * 这样可以保证 afterCommit 中的 GameContext 更新完成前，
         * 同一个业务键的新写请求不会提前进入。
         */
        public void deferUnlockAfterTransaction() {
            if (unlockDeferred) {
                return;
            }
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                return;
            }
            unlockDeferred = true;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    releaseInternal(key, holder);
                }
            });
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!unlockDeferred) {
                releaseInternal(key, holder);
            }
        }
    }

    /**
     * 锁对象包装器。
     * <p>
     * lock 负责互斥，refCount 负责生命周期管理。
     */
    private static final class LockHolder {

        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger refCount = new AtomicInteger();

        private void retain() {
            refCount.incrementAndGet();
        }

        private int release() {
            return refCount.decrementAndGet();
        }

        private void lock() {
            lock.lock();
        }

        private void unlock() {
            lock.unlock();
        }
    }
}
