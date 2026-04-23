package com.flashchat.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis 轻量级熔断器。
 * <p>
 * 要解决的问题不是「Redis 完全挂掉」，而是 Redis 进入半故障状态时：
 * 连接还能建立，但每次操作都要等超时才失败。此时如果每个请求都去尝试 Redis，
 * 业务线程会被慢故障拖住，最终放大成系统雪崩。
 * <p>
 * 三态模型：
 * 1. CLOSED    正常放行，所有请求都访问 Redis
 * 2. OPEN      熔断期间，所有请求跳过 Redis，直接走降级路径
 * 3. HALF_OPEN 冷却期后只放行一个探针请求，用于判断 Redis 是否恢复
 * <p>
 * 这里没有引入 Resilience4j，而是使用 100 行左右的定制实现，原因是：
 * 1. 诉求聚焦，只需要 Redis 熔断，不需要完整的通用治理框架
 * 2. frameworks/cache 是基础组件，依赖越轻越好
 * 3. 熔断状态切换需要与当前缓存降级语义深度配合，自实现更容易精确控制
 */
@Slf4j
public class RedisCircuitBreaker {

    /**
     * 熔断器状态。
     */
    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 连续失败多少次后触发熔断。
     */
    private final int failureThreshold;

    /**
     * 熔断持续时间（ms）。
     * 超过该时间后，OPEN 才允许转入 HALF_OPEN 进行试探。
     */
    private final long openDurationMs;

    /**
     * 当前熔断状态。
     */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /**
     * 连续失败次数，只在 CLOSED 状态下有意义。
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * 最近一次进入 OPEN 状态的时间戳。
     */
    private final AtomicLong openedAt = new AtomicLong(0L);

    /**
     * HALF_OPEN 下的探针占用标记。
     * true 表示已经有线程拿到试探资格，其余线程应继续走降级路径。
     */
    private final AtomicBoolean probing = new AtomicBoolean(false);

    /**
     * 熔断触发次数：CLOSED -> OPEN。
     */
    private final Counter tripCounter;

    /**
     * 熔断恢复次数：HALF_OPEN -> CLOSED。
     */
    private final Counter recoverCounter;

    /**
     * 试探失败次数：HALF_OPEN -> OPEN。
     */
    private final Counter probeFailCounter;

    /**
     * @param failureThreshold 连续失败阈值，建议 3~5
     * @param openDurationMs   熔断持续时间，建议 15s~60s
     * @param meterRegistry    监控注册器，为 null 时不记录熔断指标
     */
    public RedisCircuitBreaker(int failureThreshold,
                               long openDurationMs,
                               @Nullable MeterRegistry meterRegistry) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;

        if (meterRegistry != null) {
            this.tripCounter = meterRegistry.counter("cache.circuit_breaker.trip");
            this.recoverCounter = meterRegistry.counter("cache.circuit_breaker.recover");
            this.probeFailCounter = meterRegistry.counter("cache.circuit_breaker.probe_fail");
        } else {
            this.tripCounter = null;
            this.recoverCounter = null;
            this.probeFailCounter = null;
        }
    }

    /**
     * 判断当前请求是否允许访问 Redis。
     * <p>
     * CLOSED：
     * - 正常状态，直接返回 true
     * <p>
     * OPEN：
     * - 冷却期未结束：返回 false，继续降级
     * - 冷却期结束：尝试 OPEN -> HALF_OPEN，并争抢一个试探资格
     * <p>
     * HALF_OPEN：
     * - 只允许一个线程拿到试探资格
     * - 其他线程继续返回 false，避免 Redis 刚恢复时又被并发打爆
     *
     * @return true 允许访问 Redis；false 应走降级路径
     */
    public boolean allowRequest() {
        State current = state.get();

        switch (current) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - openedAt.get() >= openDurationMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        probing.set(false);
                        log.info("[熔断器] OPEN -> HALF_OPEN，开始试探 Redis 恢复");
                    }
                    return tryAcquireProbe();
                }
                return false;
            case HALF_OPEN:
                return tryAcquireProbe();
            default:
                return true;
        }
    }

    /**
     * 记录 Redis 调用成功。
     * <p>
     * CLOSED：
     * - 清空连续失败计数，说明 Redis 访问已经恢复正常
     * <p>
     * HALF_OPEN：
     * - 说明试探成功，Redis 已恢复
     * - 状态切回 CLOSED
     * - 清零失败计数
     * - 释放 probing 标记
     */
    public void recordSuccess() {
        State current = state.get();
        if (current == State.CLOSED) {
            consecutiveFailures.set(0);
            return;
        }

        if (current == State.HALF_OPEN && state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            consecutiveFailures.set(0);
            probing.set(false);
            log.info("[熔断器] HALF_OPEN -> CLOSED，Redis 已恢复");
            increment(recoverCounter);
        }
    }

    /**
     * 记录 Redis 调用失败。
     * <p>
     * CLOSED：
     * - 连续失败计数 +1
     * - 达到阈值后切到 OPEN
     * <p>
     * HALF_OPEN：
     * - 说明试探失败，Redis 仍未恢复
     * - 直接切回 OPEN，重新进入冷却窗口
     */
    public void recordFailure() {
        State current = state.get();
        if (current == State.CLOSED) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                log.warn("[熔断器] CLOSED -> OPEN，连续失败 {} 次，熔断 {}ms",
                        failures, openDurationMs);
                increment(tripCounter);
            }
            return;
        }

        if (current == State.HALF_OPEN && state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            openedAt.set(System.currentTimeMillis());
            probing.set(false);
            log.warn("[熔断器] HALF_OPEN -> OPEN，试探失败，继续熔断 {}ms", openDurationMs);
            increment(probeFailCounter);
        }
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public boolean isClosed() {
        return state.get() == State.CLOSED;
    }

    public boolean isHalfOpen() {
        return state.get() == State.HALF_OPEN;
    }

    /**
     * 以字符串形式返回当前状态，便于 Actuator / 日志 / 运维面板直接使用。
     */
    public String getState() {
        return state.get().name();
    }

    /**
     * HALF_OPEN 下争抢唯一探针资格。
     */
    private boolean tryAcquireProbe() {
        return probing.compareAndSet(false, true);
    }

    /**
     * 监控注册器为 null 时跳过指标上报，避免基础组件强依赖监控系统。
     */
    private void increment(@Nullable Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
