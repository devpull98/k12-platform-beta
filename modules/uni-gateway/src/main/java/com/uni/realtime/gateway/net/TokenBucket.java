package com.uni.realtime.gateway.net;

import java.time.Clock;
import java.time.Duration;

/**
 * Fixed-window counter: {@code capacity} permits, refilled back to full every
 * {@code refillPeriod}. Plan.md Task 7's "3/refill 1s" notation reads as a window, not a
 * gradual trickle rate -- a window is simpler to reason about and to test deterministically
 * with an injected {@link Clock}.
 *
 * <p>{@link #tryConsume()} is {@code synchronized}: {@code RateLimitHandler} only ever touches
 * one instance from one channel's event-loop thread, but {@code IpAdmissionController} (§5.6
 * L1) shares a single instance per IP across every connection from that IP, which can land on
 * different worker threads concurrently -- exactly the burst this bucket exists to catch.
 * Without the lock, concurrent callers race on the unguarded {@code available--}.
 */
final class TokenBucket {

    private final int capacity;
    private final long refillPeriodMillis;
    private final Clock clock;

    private int available;
    private long windowStartMillis;

    TokenBucket(int capacity, Duration refillPeriod, Clock clock) {
        this.capacity = capacity;
        this.refillPeriodMillis = refillPeriod.toMillis();
        this.clock = clock;
        this.available = capacity;
        this.windowStartMillis = clock.millis();
    }

    synchronized boolean tryConsume() {
        long now = clock.millis();
        if (now - windowStartMillis >= refillPeriodMillis) {
            available = capacity;
            windowStartMillis = now;
        }
        if (available <= 0) {
            return false;
        }
        available--;
        return true;
    }
}
