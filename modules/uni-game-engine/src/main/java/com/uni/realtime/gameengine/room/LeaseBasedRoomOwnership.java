package com.uni.realtime.gameengine.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 14 / ADR-007 (2026-09-07 decision): replaces {@link ModuloRoomOwnership} as Phase 1's
 * default {@link RoomOwnership} -- a pod GRABS a room's ownership through {@code roomStore}
 * instead of being ASSIGNED one by a fixed {@code room_id % N}, so scaling the Engine fleet or
 * losing a pod no longer reshuffles every room's owner (system-architecture.md §9.2 Rủi ro 4).
 *
 * <p><b>Netty EventLoop safety is the whole design constraint here.</b>
 * {@code RoomOwnershipHandler} calls {@link #isOwner}/{@link #ownerPodId} directly on the
 * EventLoop thread for every inbound frame (ADR-005 forbids blocking I/O there). This class
 * NEVER touches {@code roomStore} from those two methods -- they only ever read
 * {@link #cache}, an in-memory map, so they return in constant time regardless of external-store
 * latency or an outage. All the actual network I/O happens in {@link #ensureAcquired}, and
 * only asynchronously: it kicks off {@code roomStore.tryAcquire(...)} and returns immediately,
 * updating {@link #cache} later via {@link CompletableFuture#whenComplete}. A room this pod has
 * never seen resolves to {@link RoomOwnership#ownerPodId "" (unknown)} for the first frame or
 * two while that resolves -- {@code RoomOwnershipHandler} drops those rather than guessing.
 *
 * <p>{@link #renewAll()} is deliberately NOT self-scheduling (no internal timer/executor) --
 * something outside this class must call it periodically (roughly {@code ttl / 3}), matching
 * the split already used elsewhere in this codebase where a room's own actor owns its flush
 * timer rather than a shared class inventing a second, competing scheduling mechanism. Wiring
 * that periodic call is a follow-up task, not done here.
 */
public final class LeaseBasedRoomOwnership implements RoomOwnership {

    private static final Logger log = LoggerFactory.getLogger(LeaseBasedRoomOwnership.class);

    private final String selfPodId;
    private final RoomLeaseStore roomStore;
    private final Duration ttl;
    private final RoomOwnership fallback;

    private final Map<String, RoomLease> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RoomLease>> pending = new ConcurrentHashMap<>();

    /**
     * @param fallback used ONLY when {@code roomStore} fails with an exception (the external
     *     store unreachable) while acquiring a room this pod has never resolved before -- seeds
     *     the cache with the fallback's answer for that one room so an outage of that store
     *     degrades to Phase-1-modulo-like behavior for new rooms instead of refusing to answer
     *     at all. Typically a {@link ModuloRoomOwnership} built from the same pod list.
     */
    public LeaseBasedRoomOwnership(String selfPodId, RoomLeaseStore roomStore, Duration ttl, RoomOwnership fallback) {
        this.selfPodId = selfPodId;
        this.roomStore = roomStore;
        this.ttl = ttl;
        this.fallback = fallback;
    }

    @Override
    public void ensureAcquired(String roomId) {
        if (cache.containsKey(roomId)) {
            return;
        }
        // computeIfAbsent is atomic on ConcurrentHashMap: under concurrent frames for the same
        // brand-new roomId, exactly one acquireAsync(...) call is made, not one per frame.
        // Prove-it (2026-09-07): bypassing this straight to acquireAsync(roomId) turned exactly
        // 1 of 11 tests red (the concurrent-before-resolving one) -- confirms the test actually
        // exercises this guard rather than passing by coincidence.
        pending.computeIfAbsent(roomId, this::acquireAsync);
    }

    private CompletableFuture<RoomLease> acquireAsync(String roomId) {
        return roomStore.tryAcquire(roomId, selfPodId, ttl)
                .exceptionally(ex -> {
                    log.warn("room {}: lease store unreachable while acquiring, falling back to {}",
                            roomId, fallback.getClass().getSimpleName(), ex);
                    return new RoomLease(fallback.ownerPodId(roomId), 0);
                })
                .whenComplete((lease, ignoredEx) -> {
                    cache.put(roomId, lease);
                    pending.remove(roomId);
                });
    }

    @Override
    public boolean isOwner(String roomId) {
        RoomLease lease = cache.get(roomId);
        return lease != null && selfPodId.equals(lease.podId());
    }

    @Override
    public String ownerPodId(String roomId) {
        RoomLease lease = cache.get(roomId);
        return lease == null ? "" : lease.podId();
    }

    /**
     * The epoch this pod believes is current for {@code roomId}, for stamping
     * {@code InternalHeader.epoch} on outbound frames and snapshot writes (Task 14's Hot
     * Snapshot half). {@code 0} both for "never resolved" and for the fallback path on a store
     * outage -- Phase 1 already treats {@code epoch = 0} as "no fencing in effect" (ADR-007),
     * which is the correct, conservative answer when the real epoch could not be confirmed.
     */
    @Override
    public long epochOf(String roomId) {
        RoomLease lease = cache.get(roomId);
        return lease == null ? 0 : lease.epoch();
    }

    /**
     * Renews every lease this pod currently believes it holds. Call periodically (~ttl/3) from
     * outside this class. Any renewal that fails -- exceptionally, or by resolving
     * {@code false} -- is treated as "lease lost": removed from {@link #cache} so the next
     * {@link #ensureAcquired} call re-resolves ownership from scratch rather than this pod
     * continuing to act as owner on stale belief. This is the fencing half of the design: a
     * pod that is uncertain about its lease gives it up rather than risking two pods both
     * believing they own the same room.
     *
     * <p>Real-infra chaos test finding (docker-compose.dev.yml, kill a room's owning pod): a
     * room cached as owned by ANOTHER pod (a lost {@code tryAcquire} race) used to stay in
     * {@link #cache} forever -- nothing ever evicted it, so {@link #ensureAcquired} short-circuited
     * on the {@code cache.containsKey} check for the rest of this pod's life, even long after the
     * original owner's lease had genuinely expired. There is no cross-pod notification when a
     * lease frees up (SETNX polling is the only signal this design has), so this pod must
     * periodically retry too -- evicting foreign-owned entries here reuses the SAME cadence
     * {@code renewAll()} is already called on, rather than inventing a second timer/TTL just for
     * this. The next frame for that room (if any) triggers a fresh {@code ensureAcquired}, which
     * is exactly how a genuinely freed lease gets discovered and won.
     */
    public void renewAll() {
        cache.forEach((roomId, lease) -> {
            if (!selfPodId.equals(lease.podId())) {
                cache.remove(roomId, lease);
                return;
            }
            roomStore.renew(roomId, selfPodId, lease.epoch(), ttl).whenComplete((renewed, ex) -> {
                if (ex != null) {
                    log.warn("room {}: renew failed, treating lease as lost", roomId, ex);
                    cache.remove(roomId, lease);
                } else if (!Boolean.TRUE.equals(renewed)) {
                    log.info("room {}: lease renewal reported lost to another pod", roomId);
                    cache.remove(roomId, lease);
                }
            });
        });
    }
}
