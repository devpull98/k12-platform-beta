package com.uni.realtime.gameengine.persistence;

import com.uni.realtime.gameengine.room.GameSessionDefinitionStore;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reuses the SAME Valkey connection/codec as {@link DistributedRoomSnapshotStore}
 * ({@code RedisAsyncCommands<String, byte[]>}) -- no second connection opened, same reasoning as
 * {@link EnginePodPresence} reusing the lease connection (Task 21).
 *
 * <p><b>TTL is a pragmatic placeholder, not a real decision:</b> 7 days, chosen only so a
 * provisioned-but-never-joined room does not accumulate in Valkey forever -- this repo has no
 * real signal for "this session is over, safe to forget" yet (see {@code RoomTerminated} in
 * {@code RoomSupervisor}, which only removes the in-memory actor reference, not anything in
 * Valkey). Revisit once CMS defines an actual session lifecycle/cleanup contract.
 */
public final class DistributedGameSessionDefinitionStore implements GameSessionDefinitionStore {

    private final RedisAsyncCommands<String, byte[]> commands;

    public DistributedGameSessionDefinitionStore(RedisAsyncCommands<String, byte[]> commands) {
        this.commands = commands;
    }

    @Override
    public CompletableFuture<Void> save(String roomId, byte[] definitionJsonBytes) {
        return commands.set(definitionKey(roomId), definitionJsonBytes, SetArgs.Builder.ex(java.time.Duration.ofDays(7)))
                .toCompletableFuture()
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> load(String roomId) {
        return commands.get(definitionKey(roomId)).toCompletableFuture().thenApply(Optional::ofNullable);
    }

    private static String definitionKey(String roomId) {
        return "room:definition:" + roomId;
    }
}
