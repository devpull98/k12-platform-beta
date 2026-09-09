package com.uni.realtime.gameengine.persistence;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;

/**
 * Task 21: Engine's half of Gateway dynamic pod discovery (system-architecture.md §9.2 Rủi ro 4)
 * -- the write side {@code ValkeyEnginePodResolver} (uni-websocket-gateway) reads back. Each pod
 * periodically re-announces "I am reachable at host:port" as a TTL-bound key in the same
 * room-store (Valkey) instance already used for room leases (Task 14); no new infra, reuses the
 * exact SET-with-EX + periodic-renew idiom {@code DistributedRoomLeaseStore} already established.
 *
 * <p>Gated by the same {@code uni.engine.room-store.enabled} flag as the lease store (both need
 * the identical Valkey connection) -- see {@code EngineNetworkLifecycle}. A pod that stops
 * renewing (crash, network partition) simply expires out of the registry; nothing needs to
 * notice and delete it explicitly.
 */
public final class EnginePodPresence {

    private static final String KEY_PREFIX = "engine:pod:";

    private final RedisAsyncCommands<String, String> commands;
    private final String podId;
    private final String advertisedHostPort;
    private final Duration ttl;

    public EnginePodPresence(RedisAsyncCommands<String, String> commands, String podId,
            String advertisedHostPort, Duration ttl) {
        this.commands = commands;
        this.podId = podId;
        this.advertisedHostPort = advertisedHostPort;
        this.ttl = ttl;
    }

    public void announce() {
        commands.set(KEY_PREFIX + podId, advertisedHostPort, SetArgs.Builder.ex(ttl.toSeconds()));
    }
}
