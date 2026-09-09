package com.uni.realtime.gameengine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
