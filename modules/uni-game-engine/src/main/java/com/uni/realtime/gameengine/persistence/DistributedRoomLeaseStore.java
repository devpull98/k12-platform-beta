package com.uni.realtime.gameengine.persistence;

import com.uni.realtime.gameengine.room.RoomLease;
import com.uni.realtime.gameengine.room.RoomLeaseStore;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class DistributedRoomLeaseStore implements RoomLeaseStore {

    private static final String RENEW_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
            + "  return redis.call('EXPIRE', KEYS[1], ARGV[2]) "
            + "else "
            + "  return 0 "
            + "end";

    private final RedisAsyncCommands<String, String> commands;

    public DistributedRoomLeaseStore(RedisAsyncCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public CompletableFuture<RoomLease> tryAcquire(String roomId, String podId, Duration ttl) {
        return commands.set(ownerKey(roomId), podId, SetArgs.Builder.nx().ex(ttl.toSeconds()))
                .toCompletableFuture()
                .thenCompose(result -> "OK".equals(result)
                        ? claimedNewEpoch(roomId, podId)
                        : currentLease(roomId));
    }

    @Override
    public CompletableFuture<Boolean> renew(String roomId, String podId, long epoch, Duration ttl) {
        return commands.eval(RENEW_SCRIPT, ScriptOutputType.INTEGER,
                        new String[] {ownerKey(roomId)}, podId, String.valueOf(ttl.toSeconds()))
                .toCompletableFuture()
                .thenApply(result -> Long.valueOf(1L).equals(result));
    }

    private CompletableFuture<RoomLease> claimedNewEpoch(String roomId, String podId) {
        return commands.incr(epochKey(roomId)).toCompletableFuture()
                .thenApply(epoch -> new RoomLease(podId, epoch));
    }

    private CompletableFuture<RoomLease> currentLease(String roomId) {
        CompletableFuture<String> owner = commands.get(ownerKey(roomId)).toCompletableFuture();
        CompletableFuture<String> epoch = commands.get(epochKey(roomId)).toCompletableFuture();
        return owner.thenCombine(epoch, (podId, epochValue) ->
                new RoomLease(podId, epochValue == null ? 0L : Long.parseLong(epochValue)));
    }

    private static String ownerKey(String roomId) {
        return "room:owner:" + roomId;
    }

    private static String epochKey(String roomId) {
        return "room:epoch:" + roomId;
    }
}
