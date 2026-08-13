package com.finsight.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSidecarCircuitBreakerTest {

    @Test
    void opensAfterConsecutiveFailures() {
        AiSidecarCircuitBreaker breaker = new AiSidecarCircuitBreaker(3, Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(AiSidecarCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void transitionsToHalfOpenAfterOpenDuration() throws Exception {
        AiSidecarCircuitBreaker breaker = new AiSidecarCircuitBreaker(2, Duration.ofMillis(150));
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.tryAcquire()).isFalse();
        Thread.sleep(200);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(AiSidecarCircuitBreaker.State.HALF_OPEN);
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(AiSidecarCircuitBreaker.State.CLOSED);
    }

    @Test
    void resetsFailureCountOnSuccess() {
        AiSidecarCircuitBreaker breaker = new AiSidecarCircuitBreaker(3, Duration.ofSeconds(30));
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        assertThat(breaker.consecutiveFailures()).isZero();
    }

    @Test
    void validatesArguments() {
        assertThatThrownBy(() -> new AiSidecarCircuitBreaker(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiSidecarCircuitBreaker(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
