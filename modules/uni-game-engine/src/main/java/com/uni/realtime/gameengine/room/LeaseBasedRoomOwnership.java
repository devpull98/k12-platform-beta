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
    private final RoomOwnership fallback;

    private final Map<String, RoomLease> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RoomLease>> pending = new ConcurrentHashMap<>();

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
