package com.uni.realtime.websocketgateway.routing;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
