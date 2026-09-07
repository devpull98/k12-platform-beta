package com.uni.realtime.gateway.net;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 admission control (system-architecture.md §5.6) -- "chống DDoS thô": one {@link TokenBucket}
 * per source IP, shared across every connection this pod accepts. Unlike {@link RateLimitHandler}'s
 * per-connection buckets (already bound to one {@code student_id} by the time they run), a
 * connection at this point has no identity yet -- IP is the only key available -- so this map is
 * what makes the count cross-connection, the same shared-instance shape {@link
 * com.uni.realtime.gateway.fanout.RoomRegistry} uses for the same reason.
 *
 * <p>Threshold: 4.000 handshake/phút, a Business decision (2026-09-06) superseding the original
 * 300 written in plan.md Task 7's acceptance criteria -- see system-architecture.md §5.6 and
 * {@code _context.md}. It is an estimate from the largest known session size, not a measured
 * per-IP number (PH-1 load testing must confirm it).
 *
 * <p>Deliberately separate from {@link RateLimitHandler}'s L2 per-{@code student_id} buckets:
 * that keys legitimate, already-authenticated in-game traffic; this keys anonymous connection
 * attempts before any identity exists.
 *
 * <p>Known simplification: entries are never evicted, so a pod holds one bucket per distinct IP
 * seen for its entire lifetime. Accepted the same way {@code RouteCache} accepts "no TTL" (§8.2)
 * -- revisit only if PH-1 load testing shows it matters.
 */
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
        // TokenBucket itself is not thread-safe (RateLimitHandler doesn't need it to be -- one
        // instance per connection, one thread). This one instance is shared across every
        // concurrent connection from the same IP, which can land on different Netty worker
        // threads at once -- exactly the burst L1 exists to catch -- so tryConsume() must be
        // serialized here, at the one call site that actually shares an instance across threads.
        synchronized (bucket) {
            return bucket.tryConsume();
        }
    }
}
