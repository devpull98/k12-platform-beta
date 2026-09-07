package com.uni.realtime.engine.persistence;

import com.uni.realtime.engine.room.RoomLease;
import com.uni.realtime.engine.room.RoomLeaseStore;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Task 14: the real {@link RoomLeaseStore}, backed by whatever RESP-protocol store is
 * configured at {@code uni.engine.room-store.uri} (Valkey today -- see
 * {@code application.yml}'s comment; this class deliberately doesn't name it, so swapping the
 * backing product again is a config/ops change, not a rename across the codebase).
 *
 * <p><b>NOT verified against a real store instance.</b> This development environment has no
 * such server and no working Docker daemon (same constraint recorded against
 * {@code docker-compose.dev.yml} in plan.md Task 13) -- everything here follows Lettuce's
 * documented API but has not been exercised end-to-end. Treat this the same way
 * {@code TicketAuthHandler}'s placeholder {@code TicketVerifier} is treated: do not point it at
 * staging/production until it has actually run against the real cluster.
 *
 * <p>Two keys per room, not one, so the fencing epoch survives a lease expiring: {@code
 * room:owner:{roomId}} (the lease itself, TTL-bound) and {@code room:epoch:{roomId}} (a plain
 * counter, no TTL -- it must keep counting up across every acquisition of the same room, not
 * reset when a lease lapses).
 *
 * <p>{@link #tryAcquire} is three round trips on the conflict path (SET NX, then GET + GET),
 * not one atomic script -- there is a narrow window between the failed SET NX and the two GETs
 * where the value could change again (another pod's lease expiring and a third pod grabbing it,
 * mid-read). Worst case this reads back a momentarily stale answer, corrected on the very next
 * {@link #tryAcquire} for that room; it cannot produce two pods both believing they hold the
 * same room, because that guarantee comes entirely from the atomicity of the `SET NX` step
 * itself. A Lua script would close this window too -- documented follow-up, not done here since
 * it cannot be verified without a real instance.
 */
public final class DistributedRoomLeaseStore implements RoomLeaseStore {

    /**
     * Atomic compare-and-extend: only the pod recorded as the current holder can renew its own
     * TTL. Needs a script because "extend TTL iff value matches" has no single command.
     */
    private static final String RENEW_SCRIPT =
            // "redis" is this store's Lua scripting API global table name -- every RESP store
            // compatible with this class inherits it verbatim from Redis's original Lua engine,
            // regardless of the product's own name. Not a typo, not a leftover.
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
