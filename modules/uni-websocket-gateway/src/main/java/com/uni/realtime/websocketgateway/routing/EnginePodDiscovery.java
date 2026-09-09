package com.uni.realtime.websocketgateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Task 21: periodically asks an {@link EnginePodResolver} for the currently live Engine pod set
 * and dials any pod {@link EngineConnector} doesn't already know about -- closes
 * system-architecture.md §9.2 Rủi ro 4 ("a brand-new Engine pod Gateway never knew about at boot
 * is unreachable no matter what ownership algorithm Engine runs").
 *
 * <p>Runs on its own single daemon thread, never the Netty EventLoop (ADR-005) -- {@link
 * EnginePodResolver#resolve} may block on a network store. A pod that fails to connect this
 * cycle (unreachable, still starting up) is simply retried next cycle -- one bad pod never stops
 * the others in the same poll from being dialed.
 */
public final class EnginePodDiscovery {

    private static final Logger log = LoggerFactory.getLogger(EnginePodDiscovery.class);

    private final EnginePodResolver resolver;
    private final EngineConnector connector;
    private final Duration pollInterval;

    private ScheduledExecutorService scheduler;

    public EnginePodDiscovery(EnginePodResolver resolver, EngineConnector connector, Duration pollInterval) {
        this.resolver = resolver;
        this.connector = connector;
        this.pollInterval = pollInterval;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-engine-pod-discovery");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1, pollInterval.toMillis());
        scheduler.scheduleAtFixedRate(this::pollOnce, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void pollOnce() {
        for (EnginePodAddress pod : resolver.resolve()) {
            if (connector.isConnected(pod.podId())) {
                continue;
            }
            try {
                connector.connect(pod.podId(), pod.host(), pod.port());
                log.info("engine pod discovery: connected newly discovered pod {} ({}:{})",
                        pod.podId(), pod.host(), pod.port());
            } catch (Exception ex) {
                log.warn("engine pod discovery: failed to connect to {} ({}:{}), will retry next cycle",
                        pod.podId(), pod.host(), pod.port(), ex);
            }
        }
    }
}
