package com.uni.realtime.gameengine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link RoomSnapshotStore} for callers with no room-store wiring (Phase 1's default
 * until {@code uni.engine.room-store.enabled} is turned on) -- every read reports nothing found, and every
 * write reports {@link SnapshotWriteResult#DISABLED}, never {@code ACCEPTED}.
 *
 * <p>{@code DISABLED}, not {@code ACCEPTED}, is deliberate and load-bearing (Task 15 / B2): an
 * {@code ACCEPTED} here would be a lie -- nothing was actually persisted -- and
 * {@code RoomActor} treats {@code ACCEPTED} as license to broadcast {@code CommittedSeq}, telling
 * clients a sequence is durably safe to discard from their RingBuffer. Reporting success for a
 * write that stored nothing would manufacture exactly the false durability signal Task 15 exists
 * to prevent. Just as important, it must NOT be {@code FENCED} either -- {@code RoomActor} stops
 * itself on {@code FENCED} (zombie-actor fix), and every flush would take that branch under
 * today's default config, stopping every room in the system almost immediately. {@code DISABLED}
 * is the one outcome {@code RoomActor} takes no action at all on.
 *
 * <p>Shared by {@link RoomActor} and {@link RoomSupervisor} rather than duplicated so there is
 * exactly one no-op behavior to reason about. Public (not package-private) since Task 18's
 * wiring in {@code EngineNetworkLifecycle} (a different package) needs a real
 * {@link RoomSnapshotStore} to pass even when only Kafka, not the room store, is enabled.
 */
public final class NoopRoomSnapshotStore implements RoomSnapshotStore {
    public static final NoopRoomSnapshotStore INSTANCE = new NoopRoomSnapshotStore();

    private NoopRoomSnapshotStore() {
    }

    @Override
    public CompletableFuture<SnapshotWriteResult> save(String roomId, long epoch, byte[] envelopeBytes) {
        return CompletableFuture.completedFuture(SnapshotWriteResult.DISABLED);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> load(String roomId) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
