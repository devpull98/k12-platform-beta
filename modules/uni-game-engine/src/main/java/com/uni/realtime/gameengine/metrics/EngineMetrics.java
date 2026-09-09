package com.uni.realtime.gameengine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicInteger;


public final class EngineMetrics {

    private final Timer processingLatencyTimer;
    private final Counter channelNotWritableCounter;
    private final AtomicInteger mailboxDepth = new AtomicInteger();

    public EngineMetrics(MeterRegistry meterRegistry) {
        this.processingLatencyTimer = Timer.builder("actor_processing_latency")
                .publishPercentiles(0.5, 0.99)
                .register(meterRegistry);
        this.channelNotWritableCounter = Counter.builder("channel_not_writable_total").register(meterRegistry);
        Gauge.builder("actor_mailbox_depth", mailboxDepth, AtomicInteger::get).register(meterRegistry);
    }

    public Timer processingLatencyTimer() {
        return processingLatencyTimer;
    }

    public void recordChannelNotWritable() {
        channelNotWritableCounter.increment();
    }

    public void recordMessageEnqueued() {
        mailboxDepth.incrementAndGet();
    }

    public void recordMessageDequeued() {
        mailboxDepth.decrementAndGet();
    }
}
