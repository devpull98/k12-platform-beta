package com.uni.realtime.gameengine.persistence;

import com.uni.realtime.gameengine.room.RoomSnapshotStore;
import com.uni.realtime.gameengine.room.SnapshotWriteResult;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class DistributedRoomSnapshotStore implements RoomSnapshotStore {

    public static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    private static final String SAVE_SCRIPT =
            "local current = tonumber(redis.call('GET', KEYS[2]) or '0') "
            + "if tonumber(ARGV[2]) >= current then "
            + "  redis.call('SET', KEYS[1], ARGV[1]) "
            + "  return 1 "
            + "else "
            + "  return 0 "
            + "end";

    private final RedisAsyncCommands<String, byte[]> commands;

    public DistributedRoomSnapshotStore(RedisAsyncCommands<String, byte[]> commands) {
        this.commands = commands;
    }

    @Override
    public CompletableFuture<SnapshotWriteResult> save(String roomId, long epoch, byte[] envelopeBytes) {
        byte[] epochArg = String.valueOf(epoch).getBytes(StandardCharsets.US_ASCII);
        return commands.eval(SAVE_SCRIPT, ScriptOutputType.INTEGER,
                        new String[] {snapshotKey(roomId), epochKey(roomId)}, envelopeBytes, epochArg)
                .toCompletableFuture()
                .thenApply(result -> Long.valueOf(1L).equals(result)
                        ? SnapshotWriteResult.ACCEPTED
                        : SnapshotWriteResult.FENCED);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> load(String roomId) {
        return commands.get(snapshotKey(roomId)).toCompletableFuture().thenApply(Optional::ofNullable);
    }

    private static String snapshotKey(String roomId) {
        return "room:snap:" + roomId;
    }

    private static String epochKey(String roomId) {
        return "room:epoch:" + roomId;
    }
}
