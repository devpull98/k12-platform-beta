package com.uni.realtime.websocketgateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
