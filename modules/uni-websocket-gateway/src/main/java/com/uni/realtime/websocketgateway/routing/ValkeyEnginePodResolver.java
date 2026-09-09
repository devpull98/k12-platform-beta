package com.uni.realtime.websocketgateway.routing;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 21's chosen {@link EnginePodResolver}: reads back the registry Engine's
 * {@code EnginePodPresence} writes into the same room-store (Valkey) instance Task 14 already
 * uses for room leases -- no new infra, reuses infra already in Phase 1 scope
 * (system-architecture.md §9.2 Rủi ro 4). Each Engine pod owns a TTL-bound key
 * {@code engine:pod:<podId>} = {@code "<host>:<port>"}, re-announced on the same cadence as its
 * lease renewal; a pod that stops renewing (crash, partition) ages out of the scan below on its
 * own, nothing here needs to notice and delete it.
 *
 * <p>{@code SCAN} (cursor-based, non-blocking on the server), not {@code KEYS} -- the standard
 * production-safe substitute, even though the key count here (dozens of pods, not millions of
 * rooms) would make {@code KEYS} harmless in practice; matching the safe idiom costs nothing.
 *
 * <p>Uses blocking (sync) Lettuce commands deliberately -- this is only ever called from {@link
 * EnginePodDiscovery}'s own background poll thread, never a Netty EventLoop (ADR-005), so there
 * is no async-chaining benefit to buy here, only complexity.
 *
 * <p><b>NOT verified against a real store instance by a unit test</b> -- same convention as
 * Engine's {@code DistributedRoomLeaseStore} (Task 14): faking Lettuce's command surface buys
 * little confidence over exercising the real client. Verified instead by
 * {@code DockerComposeScaleUpIT} against a real Valkey container.
 */
public final class ValkeyEnginePodResolver implements EnginePodResolver {

    private static final Logger log = LoggerFactory.getLogger(ValkeyEnginePodResolver.class);
    private static final String KEY_PREFIX = "engine:pod:";

    private final RedisCommands<String, String> commands;

    public ValkeyEnginePodResolver(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public List<EnginePodAddress> resolve() {
        List<EnginePodAddress> pods = new ArrayList<>();
        try {
            ScanArgs args = ScanArgs.Builder.matches(KEY_PREFIX + "*").limit(1000);
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<String> result = commands.scan(cursor, args);
                for (String key : result.getKeys()) {
                    parseEntry(key, commands.get(key)).ifPresent(pods::add);
                }
                cursor = result;
            } while (!cursor.isFinished());
        } catch (RuntimeException ex) {
            log.warn("engine pod discovery: room-store unreachable this cycle, reporting no pods "
                    + "(EnginePodDiscovery keeps whatever it already connected)", ex);
            return List.of();
        }
        return pods;
    }

    private static java.util.Optional<EnginePodAddress> parseEntry(String key, String hostPort) {
        if (hostPort == null) {
            return java.util.Optional.empty(); // expired between SCAN and GET -- benign race
        }
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            log.warn("engine pod registry key {} has malformed value '{}', skipping", key, hostPort);
            return java.util.Optional.empty();
        }
        String podId = key.substring(KEY_PREFIX.length());
        try {
            int port = Integer.parseInt(hostPort.substring(colon + 1));
            return java.util.Optional.of(new EnginePodAddress(podId, hostPort.substring(0, colon), port));
        } catch (NumberFormatException ex) {
            log.warn("engine pod registry key {} has malformed value '{}', skipping", key, hostPort);
            return java.util.Optional.empty();
        }
    }
}
