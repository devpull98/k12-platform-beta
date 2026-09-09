package com.uni.realtime.websocketgateway.auth.dev;

import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

public final class DevJoinTokenReplayGuard {

    private final ConcurrentHashMap<String, Long> usedJtis = new ConcurrentHashMap<>();
    private final Clock clock;

    public DevJoinTokenReplayGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * @throws JoinTokenRejectedException {@code jti} was already marked used and has not yet expired
     */
    public void checkAndMark(String jti, long expEpochMs) throws JoinTokenRejectedException {
        usedJtis.values().removeIf(exp -> exp < clock.millis());
        Long previous = usedJtis.putIfAbsent(jti, expEpochMs);
        if (previous != null) {
            throw new JoinTokenRejectedException("dev joinToken: jti " + jti + " already used (replay)");
        }
    }
}
