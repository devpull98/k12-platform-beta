package com.uni.realtime.gameengine.room;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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
