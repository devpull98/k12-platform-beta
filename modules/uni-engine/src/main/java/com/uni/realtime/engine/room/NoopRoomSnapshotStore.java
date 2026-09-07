package com.uni.realtime.engine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link RoomSnapshotStore} for callers with no Redis wiring (Phase 1's default until
 * {@code uni.engine.redis.enabled} is turned on) -- every write reports success without storing
 * anything, every read reports nothing found. Shared by {@link RoomActor} and
 * {@link RoomSupervisor} rather than duplicated so there is exactly one no-op behavior to reason
 * about.
 */
final class NoopRoomSnapshotStore implements RoomSnapshotStore {
    static final NoopRoomSnapshotStore INSTANCE = new NoopRoomSnapshotStore();

    private NoopRoomSnapshotStore() {
    }

    @Override
    public CompletableFuture<Boolean> save(String roomId, long epoch, byte[] envelopeBytes) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> load(String roomId) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
