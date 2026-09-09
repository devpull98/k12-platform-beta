package com.uni.realtime.websocketgateway.routing;

import java.util.List;

public interface EnginePodResolver {

    /**
     * Best-effort snapshot of currently reachable Engine pods. Must never throw -- a resolver
     * unable to reach its backing source returns whatever it can (typically empty) and logs, so
     * one bad poll cycle never crashes the discovery scheduler.
     */
    List<EnginePodAddress> resolve();
}
