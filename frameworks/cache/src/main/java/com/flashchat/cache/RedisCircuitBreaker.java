package com.flashchat.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 熔断器适配层。
 * <p>
 * 对外保留原有 allowRequest / recordSuccess / recordFailure 调用方式，
 * 内部使用 Resilience4j 基于滑动窗口统计失败率和慢调用率。
 */
@Slf4j
public class RedisCircuitBreaker {

    private static final String NAME = "redis";

    private final CircuitBreaker circuitBreaker;
    private final boolean disabled;
    private final ThreadLocal<Long> callStartNanos = new ThreadLocal<>();

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
     * 兼容旧构造方式：连续失败 N 次后熔断。
     * <p>
     * 生产装配应优先使用 {@link #RedisCircuitBreaker(CircuitBreakerConfig, MeterRegistry)}，
     * 从而启用失败率和慢调用率判定。
     */
    public RedisCircuitBreaker(int failureThreshold,
                               long openDurationMs,
                               @Nullable MeterRegistry meterRegistry) {
        this(buildLegacyConfig(failureThreshold, openDurationMs), meterRegistry);
    }

    public RedisCircuitBreaker(CircuitBreakerConfig config,
                               @Nullable MeterRegistry meterRegistry) {
        this(CircuitBreaker.of(NAME, config), false, meterRegistry);
    }

    private RedisCircuitBreaker(CircuitBreaker circuitBreaker,
                                boolean disabled,
                                @Nullable MeterRegistry meterRegistry) {
        this.circuitBreaker = circuitBreaker;
        this.disabled = disabled;

        if (meterRegistry != null) {
            // 指标命名风格统一为点分隔(与 cache.local.lock.wait / cache.pending.repair.* 等保持一致)
            this.tripCounter = meterRegistry.counter("cache.circuit.breaker.trip");
            this.recoverCounter = meterRegistry.counter("cache.circuit.breaker.recover");
            this.probeFailCounter = meterRegistry.counter("cache.circuit.breaker.probe.fail");
        } else {
            this.tripCounter = null;
            this.recoverCounter = null;
            this.probeFailCounter = null;
        }

        if (!disabled) {
            registerStateTransitionEvents();
        }
    }

    public static RedisCircuitBreaker disabled() {
        return new RedisCircuitBreaker(
                CircuitBreaker.of(NAME + "-disabled", CircuitBreakerConfig.ofDefaults()),
                true,
                null
        );
    }

    /**
     * 判断当前请求是否允许访问 Redis。
     *
     * @return true 允许访问 Redis；false 应走降级路径
     */
    public boolean allowRequest() {
        if (disabled) {
            callStartNanos.set(System.nanoTime());
            return true;
        }

        boolean permitted = circuitBreaker.tryAcquirePermission();
        if (permitted) {
            callStartNanos.set(System.nanoTime());
        }
        return permitted;
    }

    /**
     * 记录 Redis 调用成功。调用耗时会用于 Resilience4j 的慢调用率统计。
     */
    public void recordSuccess() {
        if (disabled) {
            callStartNanos.remove();
            return;
        }
        circuitBreaker.onSuccess(elapsedNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 记录 Redis 调用失败。
     */
    public void recordFailure() {
        recordFailure(new RuntimeException("Redis call failed"));
    }

    /**
     * 记录 Redis 调用失败，并保留真实异常类型用于 Resilience4j 事件。
     */
    public void recordFailure(Throwable throwable) {
        if (disabled) {
            callStartNanos.remove();
            return;
        }
        circuitBreaker.onError(elapsedNanos(), TimeUnit.NANOSECONDS, throwable);
    }

    public boolean isOpen() {
        return !disabled && circuitBreaker.getState() == CircuitBreaker.State.OPEN;
    }

    public boolean isClosed() {
        return disabled || circuitBreaker.getState() == CircuitBreaker.State.CLOSED;
    }

    public boolean isHalfOpen() {
        return !disabled && circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN;
    }

    /**
     * 以字符串形式返回当前状态，便于 Actuator / 日志 / 运维面板直接使用。
     */
    public String getState() {
        return disabled ? "DISABLED" : circuitBreaker.getState().name();
    }

    private long elapsedNanos() {
        Long startNanos = callStartNanos.get();
        callStartNanos.remove();
        if (startNanos == null) {
            return 0L;
        }
        return Math.max(0L, System.nanoTime() - startNanos);
    }

    private void registerStateTransitionEvents() {
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            switch (event.getStateTransition()) {
                case CLOSED_TO_OPEN:
                    log.warn("[熔断器] CLOSED -> OPEN，Redis 失败率或慢调用率达到阈值");
                    increment(tripCounter);
                    break;
                case OPEN_TO_HALF_OPEN:
                    log.info("[熔断器] OPEN -> HALF_OPEN，开始试探 Redis 恢复");
                    break;
                case HALF_OPEN_TO_CLOSED:
                    log.info("[熔断器] HALF_OPEN -> CLOSED，Redis 已恢复");
                    increment(recoverCounter);
                    break;
                case HALF_OPEN_TO_OPEN:
                    log.warn("[熔断器] HALF_OPEN -> OPEN，试探失败或仍然过慢");
                    increment(probeFailCounter);
                    break;
                default:
                    break;
            }
        });
    }

    private static CircuitBreakerConfig buildLegacyConfig(int failureThreshold, long openDurationMs) {
        int threshold = Math.max(1, failureThreshold);
        long waitMs = Math.max(1L, openDurationMs);
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(100.0F)
                .slowCallRateThreshold(100.0F)
                .slowCallDurationThreshold(Duration.ofDays(3650))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(threshold)
                .minimumNumberOfCalls(threshold)
                .permittedNumberOfCallsInHalfOpenState(1)
                .waitDurationInOpenState(Duration.ofMillis(waitMs))
                .build();
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
