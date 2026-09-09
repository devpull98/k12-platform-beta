package com.uni.realtime.websocketgateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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

    public void recordHandshake() {
        handshakeCounter.increment();
    }

    public Timer fanoutLatencyTimer() {
        return fanoutLatencyTimer;
    }
}
