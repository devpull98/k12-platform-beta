package com.uni.realtime.engine.persistence;

import com.uni.realtime.engine.room.RoomSnapshotStore;
import com.uni.realtime.engine.room.SnapshotWriteResult;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Task 14: the real, Redis-backed {@link RoomSnapshotStore}.
 *
 * <p><b>NOT verified against a real Redis instance</b> -- same caveat as
 * {@link RedisRoomLeaseStore}: no Redis server and no working Docker daemon in this development
 * environment. Follows Lettuce's documented API but has not run end-to-end. Do not point this
 * at staging/production before it has.
 *
 * <p>Needs a connection keyed and valued as raw bytes ({@link #CODEC}) rather than the
 * {@code String} codec {@link RedisRoomLeaseStore} uses -- a snapshot envelope is opaque binary
 * (hand-rolled per {@code SnapshotEnvelope}), not UTF-8 text, and forcing it through a String
 * codec would corrupt bytes that happen not to be valid UTF-8.
 *
 * <p>{@link #save} fences against {@code room:epoch:{roomId}} -- the SAME counter
 * {@link RedisRoomLeaseStore} increments on every new lease acquisition, deliberately shared
 * rather than duplicated, so there is exactly one source of truth for "which epoch owns this
 * room now" across both the lease and the snapshot subsystems. A write whose {@code epoch} is
 * older than that counter is rejected -- the zombie-actor protection §5.8 asks for.
 */
public final class RedisSnapshotStore implements RoomSnapshotStore {

    /** Convenience for callers wiring a connection: the exact codec this store requires. */
    public static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    /**
     * KEYS[1] = snapshot key, KEYS[2] = shared epoch key. ARGV[1] = envelope bytes, ARGV[2] =
     * epoch as a decimal string -- Lua's {@code tonumber} parses that regardless of the value
     * codec used to carry it. A missing epoch key (room never had a lease acquired through
     * {@code RedisRoomLeaseStore}) reads as {@code 0}, so the very first write always succeeds.
     */
    private static final String SAVE_SCRIPT =
            "local current = tonumber(redis.call('GET', KEYS[2]) or '0') "
            + "if tonumber(ARGV[2]) >= current then "
            + "  redis.call('SET', KEYS[1], ARGV[1]) "
            + "  return 1 "
            + "else "
            + "  return 0 "
            + "end";

    private final RedisAsyncCommands<String, byte[]> commands;

    public RedisSnapshotStore(RedisAsyncCommands<String, byte[]> commands) {
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
