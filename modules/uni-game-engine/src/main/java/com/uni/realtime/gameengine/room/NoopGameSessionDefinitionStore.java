package com.uni.realtime.gameengine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Phase 1 test default (mirrors {@link NoopRoomSnapshotStore}) -- always reports "nothing
 * provisioned", so every room falls back to SOLO exactly like before this feature existed. */
public final class NoopGameSessionDefinitionStore implements GameSessionDefinitionStore {

    public static final NoopGameSessionDefinitionStore INSTANCE = new NoopGameSessionDefinitionStore();

    private NoopGameSessionDefinitionStore() {}

    @Override
    public CompletableFuture<Void> save(String roomId, byte[] definitionJsonBytes) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> load(String roomId) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
