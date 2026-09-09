package com.uni.realtime.websocketgateway.net;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;

public final class IpAdmissionController {

    private static final int CAPACITY = 4_000;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Clock clock;
    private final Cache<String, TokenBucket> bucketsByIp;

    public IpAdmissionController() {
        this(Clock.systemUTC());
    }

    IpAdmissionController(Clock clock) {
        this.clock = clock;
        this.bucketsByIp = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(5))
                .maximumSize(50_000)
                .build();
    }

    /** @return true if this handshake attempt from {@code ip} is admitted, false if it must be rejected. */
    public boolean tryAdmit(String ip) {
        TokenBucket bucket = bucketsByIp.get(ip, unused -> new TokenBucket(CAPACITY, REFILL_PERIOD, clock));
        synchronized (bucket) {
            return bucket.tryConsume();
        }
    }
}
