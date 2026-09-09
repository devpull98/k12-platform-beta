package com.uni.realtime.websocketgateway.net;

import java.time.Clock;
import java.time.Duration;

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
