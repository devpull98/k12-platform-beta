package com.uni.realtime.gameengine.persistence;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;

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
