package com.finsight.api;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory token-bucket rate limiter used for auth and write endpoints.
 *
 * <p>The implementation keeps one bucket per key and refills at a fixed rate. It is intentionally
 * lightweight so the lightweight preview mode can run without Redis. Production deployments that
 * scale horizontally should swap this for a Redis-backed equivalent behind the same interface.
 */
public class RateLimiter {
    private final int capacity;
    private final long refillIntervalNanos;
    private final Duration window;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int capacity, Duration window) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.capacity = capacity;
        this.window = window;
        this.refillIntervalNanos = Math.max(1, window.toNanos() / capacity);
    }

    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, System.nanoTime()));
        return bucket.tryConsume(System.nanoTime(), refillIntervalNanos, capacity);
    }

    public int capacity() {
        return capacity;
    }

    public Duration window() {
        return window;
    }

    private static final class Bucket {
        private final AtomicInteger tokens;
        private volatile long lastRefillNanos;

        Bucket(int initial, long now) {
            this.tokens = new AtomicInteger(initial);
            this.lastRefillNanos = now;
        }

        synchronized boolean tryConsume(long now, long refillIntervalNanos, int capacity) {
            if (now - lastRefillNanos >= refillIntervalNanos) {
                int refill = (int) ((now - lastRefillNanos) / refillIntervalNanos);
                int current = Math.min(capacity, tokens.get() + refill);
                tokens.set(current);
                lastRefillNanos += (long) refill * refillIntervalNanos;
            }
            int current = tokens.get();
            if (current <= 0) {
                return false;
            }
            tokens.set(current - 1);
            return true;
        }
    }
}
