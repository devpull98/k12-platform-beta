package com.uni.realtime.websocketgateway.net;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public final class IpAdmissionController {

    private static final int CAPACITY = 4_000;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Clock clock;
    private final ConcurrentHashMap<String, TokenBucket> bucketsByIp = new ConcurrentHashMap<>();

    public IpAdmissionController() {
        this(Clock.systemUTC());
    }

    IpAdmissionController(Clock clock) {
        this.clock = clock;
    }

    /** @return true if this handshake attempt from {@code ip} is admitted, false if it must be rejected. */
    public boolean tryAdmit(String ip) {
        TokenBucket bucket = bucketsByIp.computeIfAbsent(ip, unused -> new TokenBucket(CAPACITY, REFILL_PERIOD, clock));
        synchronized (bucket) {
            return bucket.tryConsume();
        }
    }
}
