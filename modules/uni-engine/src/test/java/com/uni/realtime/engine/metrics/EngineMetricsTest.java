package com.uni.realtime.engine.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12 verification: every metric must exist in the registry the instant
 * {@link EngineMetrics} is constructed -- a scrape before any traffic must already see it.
 */
class EngineMetricsTest {

    @Test
    void should_registerAllMetricsImmediately_when_constructed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new EngineMetrics(registry);

        assertThat(registry.find("actor_processing_latency").timer()).isNotNull();
        assertThat(registry.find("actor_mailbox_depth").gauge()).isNotNull();
        assertThat(registry.find("channel_not_writable_total").counter()).isNotNull();
    }

    @Test
    void should_startMailboxDepthAtZero_when_nothingHasBeenWiredYet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new EngineMetrics(registry);

        assertThat(registry.find("actor_mailbox_depth").gauge().value()).isZero();
    }

    @Test
    void should_trackMailboxDepth_when_enqueuedAndDequeued() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EngineMetrics metrics = new EngineMetrics(registry);

        metrics.recordMessageEnqueued();
        metrics.recordMessageEnqueued();
        assertThat(registry.find("actor_mailbox_depth").gauge().value()).isEqualTo(2.0);

        metrics.recordMessageDequeued();
        assertThat(registry.find("actor_mailbox_depth").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void should_incrementChannelNotWritableCounter_when_recorded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EngineMetrics metrics = new EngineMetrics(registry);

        metrics.recordChannelNotWritable();
        metrics.recordChannelNotWritable();

        assertThat(registry.find("channel_not_writable_total").counter().count()).isEqualTo(2.0);
    }

    @Test
    void should_recordProcessingLatency_when_timed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EngineMetrics metrics = new EngineMetrics(registry);

        metrics.processingLatencyTimer().record(5, TimeUnit.MILLISECONDS);

        assertThat(metrics.processingLatencyTimer().count()).isEqualTo(1);
        assertThat(metrics.processingLatencyTimer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5.0);
    }
}
