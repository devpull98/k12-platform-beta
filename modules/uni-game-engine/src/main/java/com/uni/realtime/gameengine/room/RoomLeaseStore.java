package com.uni.realtime.gameengine.room;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Task 14: the narrow slice of external-store access {@link LeaseBasedRoomOwnership} needs, kept
 * as an interface so the ownership/caching/fallback logic is testable without a real store
 * connection (the production implementation, {@code DistributedRoomLeaseStore}, lives in
 * {@code engine.persistence} and is not exercised by any test in this repo -- there is no such
 * store available in this development environment to verify it against, the same caveat
 * {@code JoinTokenAuthHandler}'s placeholder verifier already carries for its own real
 * implementation).
 *
 * <p>Both methods return a {@link CompletableFuture} on purpose: whatever calls this sits one
 * hop away from the Netty EventLoop (via {@link LeaseBasedRoomOwnership#ensureAcquired}),
 * which must never block (ADR-005). A synchronous, blocking interface here would make that
 * impossible to guarantee at the call site.
 */
public interface RoomLeaseStore {

    /**
     * Attempts to become the owner of {@code roomId}. Resolves to the lease that is actually
     * in effect afterward -- {@code podId} as the holder with a freshly incremented epoch if
     * this call won, or whoever already held it (unchanged) if it did not. Callers must not
     * assume winning just because the future completed without an exception.
     */
    CompletableFuture<RoomLease> tryAcquire(String roomId, String podId, Duration ttl);

    /**
     * Extends the TTL of an existing lease, but only if {@code podId}+{@code epoch} still
     * matches what the store holds. Resolves {@code true} if renewed, {@code false} if the
     * lease was already lost to someone else (or expired and nobody holds it) -- the caller
     * (see {@link LeaseBasedRoomOwnership#renewAll()}) must treat {@code false}, and any
     * exceptional completion, as "lease lost" and re-acquire from scratch rather than keep
     * believing it still owns the room.
     */
    CompletableFuture<Boolean> renew(String roomId, String podId, long epoch, Duration ttl);
}
