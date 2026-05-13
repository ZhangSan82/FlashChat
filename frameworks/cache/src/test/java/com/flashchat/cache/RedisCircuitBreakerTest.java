package com.flashchat.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCircuitBreakerTest {

    @Test
    void shouldOpenAfterThresholdAndRecoverAfterSuccessfulProbe() throws Exception {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(
                2,
                20,
                new SimpleMeterRegistry()
        );

        assertTrue(circuitBreaker.allowRequest());

        circuitBreaker.recordFailure();
        assertTrue(circuitBreaker.isClosed());

        assertTrue(circuitBreaker.allowRequest());
        circuitBreaker.recordFailure();
        assertTrue(circuitBreaker.isOpen());
        assertFalse(circuitBreaker.allowRequest());

        Thread.sleep(30);

        assertTrue(circuitBreaker.allowRequest());
        assertFalse(circuitBreaker.allowRequest());

        circuitBreaker.recordSuccess();

        assertTrue(circuitBreaker.isClosed());
        assertTrue(circuitBreaker.allowRequest());
    }

    @Test
    void shouldOpenWhenSlowSuccessfulCallsReachThreshold() throws Exception {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(100.0F)
                        .slowCallRateThreshold(50.0F)
                        .slowCallDurationThreshold(Duration.ofMillis(10))
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .waitDurationInOpenState(Duration.ofMillis(20))
                        .build(),
                new SimpleMeterRegistry()
        );

        for (int i = 0; i < 4; i++) {
            assertTrue(circuitBreaker.allowRequest());
            Thread.sleep(15);
            circuitBreaker.recordSuccess();
        }

        assertTrue(circuitBreaker.isOpen());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    void shouldOpenWhenIntermittentFailuresReachFailureRateThreshold() {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50.0F)
                        .slowCallRateThreshold(100.0F)
                        .slowCallDurationThreshold(Duration.ofSeconds(30))
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .waitDurationInOpenState(Duration.ofMillis(20))
                        .build(),
                new SimpleMeterRegistry()
        );

        assertTrue(circuitBreaker.allowRequest());
        circuitBreaker.recordFailure(new RuntimeException("redis timeout"));
        assertTrue(circuitBreaker.allowRequest());
        circuitBreaker.recordSuccess();
        assertTrue(circuitBreaker.allowRequest());
        circuitBreaker.recordFailure(new RuntimeException("redis timeout"));
        assertTrue(circuitBreaker.allowRequest());
        circuitBreaker.recordSuccess();

        assertTrue(circuitBreaker.isOpen());
        assertFalse(circuitBreaker.allowRequest());
    }
}
