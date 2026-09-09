package com.uni.realtime.websocketgateway.routing;

import java.util.List;

/**
 * Task 21: how the Gateway learns about Engine pods beyond the static
 * {@code uni.gateway.engine.pods} boot-time list read once by {@code GatewayNetworkLifecycle}
 * (system-architecture.md §9.2 Rủi ro 4 -- "a brand-new Engine pod Gateway never knew about at
 * boot is unreachable no matter what ownership algorithm Engine runs"). Called periodically from
 * {@link EnginePodDiscovery}'s own background scheduler -- NEVER from a Netty EventLoop
 * (ADR-005) -- so implementations may block or hit a network store freely.
 *
 * <p>Exactly one implementation exists today, {@link ValkeyEnginePodResolver}: Engine pods
 * self-announce into the same room-store (Valkey) instance Task 14 already uses, and this reads
 * that registry back -- no new infra, reuses infra already in Phase 1 scope. A Kubernetes-native
 * implementation (headless Service DNS, multiple A records per name) is a real, different
 * mechanism -- deliberately NOT built here, because this repo has no decided StatefulSet/headless
 * -Service topology to implement or verify it against (system-architecture.md has zero mentions
 * of either). Add it behind this same interface once that topology is actually chosen; do not
 * guess it now.
 */
public interface EnginePodResolver {

    /**
     * Best-effort snapshot of currently reachable Engine pods. Must never throw -- a resolver
     * unable to reach its backing source returns whatever it can (typically empty) and logs, so
     * one bad poll cycle never crashes the discovery scheduler.
     */
    List<EnginePodAddress> resolve();
}
