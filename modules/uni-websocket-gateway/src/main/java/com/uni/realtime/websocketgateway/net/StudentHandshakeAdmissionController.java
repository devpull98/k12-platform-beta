package com.uni.realtime.websocketgateway.net;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public final class StudentHandshakeAdmissionController {

    private static final int CAPACITY = 10;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Clock clock;
    private final ConcurrentHashMap<String, TokenBucket> bucketsByStudentId = new ConcurrentHashMap<>();

    public StudentHandshakeAdmissionController() {
        this(Clock.systemUTC());
    }

    StudentHandshakeAdmissionController(Clock clock) {
        this.clock = clock;
    }

    /** @return true if this handshake attempt from {@code studentId} is admitted, false if it must be rejected. */
    public boolean tryAdmit(String studentId) {
        TokenBucket bucket = bucketsByStudentId.computeIfAbsent(studentId, unused -> new TokenBucket(CAPACITY, REFILL_PERIOD, clock));
        synchronized (bucket) {
            return bucket.tryConsume();
        }
    }
}
