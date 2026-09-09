package com.uni.realtime.websocketgateway.net;

import java.time.Clock;
import java.time.Duration;

final class TokenBucket {

    private final double capacity;
    private final long refillPeriodMillis;
    private final Clock clock;

    private double availableTokens;
    private long lastRefillMillis;

    TokenBucket(int capacity, Duration refillPeriod, Clock clock) {
        this.capacity = capacity;
        this.refillPeriodMillis = Math.max(1, refillPeriod.toMillis());
        this.clock = clock;
        this.availableTokens = capacity;
        this.lastRefillMillis = clock.millis();
    }

    synchronized boolean tryConsume() {
        long now = clock.millis();
        long elapsedMillis = now - lastRefillMillis;
        if (elapsedMillis > 0) {
            double tokensToAdd = (double) elapsedMillis * capacity / refillPeriodMillis;
            this.availableTokens = Math.min(capacity, this.availableTokens + tokensToAdd);
            this.lastRefillMillis = now;
        }
        if (this.availableTokens < 1.0) {
            return false;
        }
        this.availableTokens -= 1.0;
        return true;
    }
}
