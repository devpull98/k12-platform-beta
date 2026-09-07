package com.uni.realtime.websocketgateway.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12 verification: every metric must exist in the registry the instant
 * {@link GatewayMetrics} is constructed -- a scrape before any traffic must already see it.
 */
class GatewayMetricsTest {

    @Test
    void should_registerAllMetricsImmediately_when_constructed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new GatewayMetrics(registry);

        assertThat(registry.find("channel_not_writable_total").counter()).isNotNull();
        assertThat(registry.find("handshake_rate").counter()).isNotNull();
        assertThat(registry.find("fanout_latency").timer()).isNotNull();
    }

    @Test
    void should_incrementHandshakeCounter_when_recorded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics(registry);

        metrics.recordHandshake();
        metrics.recordHandshake();
        metrics.recordHandshake();

        assertThat(registry.find("handshake_rate").counter().count()).isEqualTo(3.0);
    }

    @Test
    void should_incrementChannelNotWritableCounter_when_recorded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics(registry);

        metrics.recordChannelNotWritable();

        assertThat(registry.find("channel_not_writable_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void should_recordFanoutLatency_when_timed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics(registry);

        metrics.fanoutLatencyTimer().record(3, TimeUnit.MILLISECONDS);

        assertThat(metrics.fanoutLatencyTimer().count()).isEqualTo(1);
        assertThat(metrics.fanoutLatencyTimer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3.0);
    }
}
