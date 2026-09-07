package com.uni.realtime.engine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link RoomSnapshotStore} for callers with no Redis wiring (Phase 1's default until
 * {@code uni.engine.redis.enabled} is turned on) -- every read reports nothing found, and every
 * write reports {@code false} ("not committed"), never {@code true}.
 *
 * <p>{@code false}, not {@code true}, is deliberate and load-bearing (Task 15 / B2): a
 * {@code true} here would be a lie -- nothing was actually persisted -- and {@code RoomActor}
 * treats a {@code true} result as license to broadcast {@code CommittedSeq}, telling clients a
 * sequence is durably safe to discard from their RingBuffer. Reporting success for a write that
 * stored nothing would manufacture exactly the false durability signal Task 15 exists to
 * prevent, worse than not having {@code CommittedSeq} at all.
 *
 * <p>Shared by {@link RoomActor} and {@link RoomSupervisor} rather than duplicated so there is
 * exactly one no-op behavior to reason about.
 */
final class NoopRoomSnapshotStore implements RoomSnapshotStore {
    static final NoopRoomSnapshotStore INSTANCE = new NoopRoomSnapshotStore();

    private NoopRoomSnapshotStore() {
    }

    @Override
    public CompletableFuture<Boolean> save(String roomId, long epoch, byte[] envelopeBytes) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> load(String roomId) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
