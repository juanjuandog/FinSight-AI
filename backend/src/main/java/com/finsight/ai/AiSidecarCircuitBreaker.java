package com.finsight.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal circuit breaker for the AI sidecar. It opens the circuit after
 * {@code failureThreshold} consecutive failures, stays open for {@code openDuration},
 * then transitions to half-open and lets a single probe through.
 *
 * <p>The breaker is intentionally synchronous and in-memory so it works alongside the
 * existing deterministic fallback path. A redis-backed breaker can replace this class
 * later for multi-instance deployments.
 */
@Component
public class AiSidecarCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(AiSidecarCircuitBreaker.class);

    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public AiSidecarCircuitBreaker() {
        this(3, Duration.ofSeconds(30));
    }

    public AiSidecarCircuitBreaker(int failureThreshold, Duration openDuration) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold must be positive");
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero())
            throw new IllegalArgumentException("openDuration must be positive");
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public boolean tryAcquire() {
        State current = state.get();
        return switch (current) {
            case CLOSED, HALF_OPEN -> true;
            case OPEN -> {
                Instant opened = openedAt.get();
                if (opened != null && Instant.now().isAfter(opened.plus(openDuration))) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        log.info("AI sidecar circuit transitioning to HALF_OPEN");
                    }
                    yield true;
                }
                yield false;
            }
        };
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            log.info("AI sidecar circuit closed after successful probe");
        }
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAt.set(Instant.now());
            log.warn("AI sidecar circuit opened after {} consecutive failures", failures);
        } else if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            openedAt.set(Instant.now());
            log.warn("AI sidecar circuit re-opened after failed probe");
        }
    }

    public State state() {
        return state.get();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }
}
