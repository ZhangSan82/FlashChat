package com.flashchat.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

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
}
