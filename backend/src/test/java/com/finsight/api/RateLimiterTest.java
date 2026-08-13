package com.finsight.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("k1")).isTrue();
        assertThat(limiter.tryAcquire("k1")).isTrue();
        assertThat(limiter.tryAcquire("k1")).isTrue();
        assertThat(limiter.tryAcquire("k1")).isFalse();
        assertThat(limiter.tryAcquire("k2")).isTrue();
    }

    @Test
    void refillsAfterWindow() throws Exception {
        RateLimiter limiter = new RateLimiter(2, Duration.ofMillis(200));
        assertThat(limiter.tryAcquire("k1")).isTrue();
        assertThat(limiter.tryAcquire("k1")).isTrue();
        assertThat(limiter.tryAcquire("k1")).isFalse();
        Thread.sleep(120);
        assertThat(limiter.tryAcquire("k1")).isTrue();
    }

    @Test
    void validatesArguments() {
        assertThatThrownBy(() -> new RateLimiter(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
