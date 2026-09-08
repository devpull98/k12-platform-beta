package com.uni.realtime.websocketgateway.net;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 handshake admission control (system-architecture.md §5.6): one {@link TokenBucket} per
 * {@code student_id}, shared across every connection this pod accepts. Unlike {@link
 * IpAdmissionController}'s L1 budget -- which must stay generous because a whole school shares
 * one NAT IP (§5.6, "Cả trường ra Internet qua một IP NAT") -- this keys by the student identity
 * a join token has just verified, so one student reconnecting too often can never exhaust a budget
 * shared with classmates behind the same NAT IP. Runs in {@link
 * com.uni.realtime.websocketgateway.auth.JoinTokenAuthHandler}, right after {@code joinTokenVerifier.verify}
 * succeeds and {@code student_id} becomes known, before {@link ChannelAttributes} is bound.
 *
 * <p>Threshold: 10 handshake/phút (§5.6 L2) -- distinct from {@link
 * com.uni.realtime.websocketgateway.net.RateLimitHandler}'s in-game message limits (also keyed by
 * {@code student_id}, but per already-open connection); this one governs how often a student may
 * open a NEW connection at all.
 *
 * <p>Known simplification, same as {@link IpAdmissionController}: entries are never evicted, so
 * a pod holds one bucket per distinct student id seen for its entire lifetime -- acceptable at a
 * real student population's scale, revisit only if that changes.
 */
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
        // Same reasoning as IpAdmissionController.tryAdmit: this one instance is shared across
        // every connection attempt from the same student, which can land on different Netty
        // worker threads at once, so tryConsume() must be serialized here.
        synchronized (bucket) {
            return bucket.tryConsume();
        }
    }
}
