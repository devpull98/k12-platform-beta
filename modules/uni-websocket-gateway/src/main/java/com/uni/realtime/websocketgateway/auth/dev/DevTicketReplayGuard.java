package com.uni.realtime.websocketgateway.auth.dev;

import com.uni.realtime.websocketgateway.auth.TicketRejectedException;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, single-pod, local-Docker-only approximation of the real one-time ticket-replay
 * guard (system-architecture.md: {@code SET ticket:{jti} 1 EX 30 NX} against the room-store
 * cluster -- Valkey in production today, see CLAUDE.md).
 *
 * <p><b>This is NOT that guard.</b> It gives zero protection across multiple gateway pods or a
 * process restart, and it deliberately stays synchronous/in-memory rather than calling that
 * store: {@link com.uni.realtime.websocketgateway.auth.TicketVerifier#verify} runs on a Netty EventLoop
 * thread ({@code TicketAuthHandler.channelRead0}), and CLAUDE.md's hard rule ("No DB/store/HTTP
 * call inside a Netty EventLoop") forbids a real round trip to it there without first turning
 * {@code TicketVerifier} into an asynchronous interface everywhere it's called -- a real
 * interface change out of scope for local test infrastructure. This class exists only so a
 * "replay a used ticket, expect rejection" scenario is exercisable locally; never read it as
 * validating the real distributed design.
 */
public final class DevTicketReplayGuard {

    private final ConcurrentHashMap<String, Long> usedJtis = new ConcurrentHashMap<>();
    private final Clock clock;

    public DevTicketReplayGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * @throws TicketRejectedException {@code jti} was already marked used and has not yet expired
     */
    public void checkAndMark(String jti, long expEpochMs) throws TicketRejectedException {
        usedJtis.values().removeIf(exp -> exp < clock.millis());
        Long previous = usedJtis.putIfAbsent(jti, expEpochMs);
        if (previous != null) {
            throw new TicketRejectedException("dev ticket: jti " + jti + " already used (replay)");
        }
    }
}
