package com.uni.realtime.gameengine.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class LeaseBasedRoomOwnership implements RoomOwnership {

    private static final Logger log = LoggerFactory.getLogger(LeaseBasedRoomOwnership.class);

    private final String selfPodId;
    private final RoomLeaseStore roomStore;
    private final Duration ttl;

    private final Map<String, RoomLease> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RoomLease>> pending = new ConcurrentHashMap<>();

    public LeaseBasedRoomOwnership(String selfPodId, RoomLeaseStore roomStore, Duration ttl) {
        this.selfPodId = selfPodId;
        this.roomStore = roomStore;
        this.ttl = ttl;
    }

    @Override
    public void ensureAcquired(String roomId) {
        if (cache.containsKey(roomId)) {
            return;
        }
        // The mapping function below must NOT touch `pending` itself -- ConcurrentHashMap
        // forbids a computeIfAbsent mapping function from mutating the same map for the same
        // key it's computing (throws "Recursive update"), which a synchronously-completing
        // store (any fake in tests; conceivably a real one under some conditions) would trigger
        // immediately, since attaching whenComplete to an already-completed future runs the
        // callback inline. So the mapping function only starts the acquisition; the cache/pending
        // bookkeeping below runs after computeIfAbsent has already returned.
        boolean[] startedNewAcquire = {false};
        CompletableFuture<RoomLease> future = pending.computeIfAbsent(roomId, id -> {
            startedNewAcquire[0] = true;
            return roomStore.tryAcquire(id, selfPodId, ttl);
        });
        if (startedNewAcquire[0]) {
            attachCompletion(roomId, future);
        }
    }

    /**
     * On a store outage, this pod stays deliberately unresolved for {@code roomId} rather than
     * guessing an owner -- nothing is written to {@link #cache}, so {@link #ownerPodId} keeps
     * answering {@code ""} (per its documented "drop this frame" contract) and the next
     * {@link #ensureAcquired} call retries against the store instead of trusting a stale guess.
     */
    private void attachCompletion(String roomId, CompletableFuture<RoomLease> future) {
        future.whenComplete((lease, ex) -> {
            pending.remove(roomId, future);
            if (ex != null) {
                log.warn("room {}: lease store unreachable while acquiring -- ownership stays "
                        + "unresolved, will retry on the next frame for this room", roomId, ex);
                return;
            }
            cache.put(roomId, lease);
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

    @Override
    public long epochOf(String roomId) {
        RoomLease lease = cache.get(roomId);
        return lease == null ? 0 : lease.epoch();
    }

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
