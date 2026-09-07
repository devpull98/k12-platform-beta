package com.uni.realtime.gateway.net;

import java.time.Clock;
import java.time.Duration;

/**
 * Fixed-window counter: {@code capacity} permits, refilled back to full every
 * {@code refillPeriod}. Plan.md Task 7's "3/refill 1s" notation reads as a window, not a
 * gradual trickle rate -- a window is simpler to reason about and to test deterministically
 * with an injected {@link Clock}.
 *
 * <p>Deliberately NOT synchronized here: {@code RateLimitHandler} touches one instance per
 * connection from that one channel's event-loop thread only, and that path runs on every
 * SUBMIT_ANSWER -- the hottest message type in the system. {@code IpAdmissionController} (§5.6
 * L1) is the one caller that genuinely shares a single instance per IP across concurrent
 * connections/threads; it synchronizes on its own instance at the call site instead, so the
 * lock only costs anything where sharing actually happens.
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

    boolean tryConsume() {
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
