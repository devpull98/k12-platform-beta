package com.uni.realtime.websocketgateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * system-architecture.md §15.1 metrics for the Gateway pod, all registered eagerly in this
 * constructor (plan.md Task 12) so every one of them appears in a {@code /actuator/prometheus}
 * scrape from process start -- not only after the first connection or broadcast exercises a
 * handler that used to register its own metric lazily on first use.
 */
public final class GatewayMetrics {

    private final Counter channelNotWritableCounter;
    private final Counter handshakeCounter;
    private final Timer fanoutLatencyTimer;

    public GatewayMetrics(MeterRegistry meterRegistry) {
        this.channelNotWritableCounter = Counter.builder("channel_not_writable_total").register(meterRegistry);
        this.handshakeCounter = Counter.builder("handshake_rate").register(meterRegistry);
        this.fanoutLatencyTimer = Timer.builder("fanout_latency")
                .publishPercentiles(0.5, 0.99)
                .register(meterRegistry);
    }

    public void recordChannelNotWritable() {
        channelNotWritableCounter.increment();
    }

    /** Called once per successful handshake (JoinTokenAuthHandler) -- PromQL's rate() over this gives handshake_rate its name. */
    public void recordHandshake() {
        handshakeCounter.increment();
    }

    public Timer fanoutLatencyTimer() {
        return fanoutLatencyTimer;
    }
}
