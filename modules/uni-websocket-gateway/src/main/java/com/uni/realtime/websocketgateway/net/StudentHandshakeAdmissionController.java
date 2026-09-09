package com.uni.realtime.websocketgateway.net;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;

public final class StudentHandshakeAdmissionController {

    private static final int CAPACITY = 10;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Clock clock;
    private final Cache<String, TokenBucket> bucketsByStudentId;

    public StudentHandshakeAdmissionController() {
        this(Clock.systemUTC());
    }

    StudentHandshakeAdmissionController(Clock clock) {
        this.clock = clock;
        this.bucketsByStudentId = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(5))
                .maximumSize(50_000)
                .build();
    }

    /** @return true if this handshake attempt from {@code studentId} is admitted, false if it must be rejected. */
    public boolean tryAdmit(String studentId) {
        TokenBucket bucket = bucketsByStudentId.get(studentId, unused -> new TokenBucket(CAPACITY, REFILL_PERIOD, clock));
        synchronized (bucket) {
            return bucket.tryConsume();
        }
    }
}
