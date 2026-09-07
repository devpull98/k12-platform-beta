package com.uni.realtime.gameengine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * system-architecture.md §15.1 metrics for the Engine pod, all registered eagerly in this
 * constructor so every one of them appears in a {@code /actuator/prometheus} scrape from
 * process start (plan.md Task 12: "không chờ tới cuối mới gắn"). Pod-wide, not per-room: a
 * per-room tag would mean the metric can't exist before a room does, defeating "present from
 * startup" -- Prometheus already computes cross-room percentiles from one histogram at query
 * time.
 */
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

    /**
     * Not called by any production code path yet: nothing sends a message into a real
     * RoomActor's mailbox from outside a test (that ingress is Task 13's job). The gauge
     * honestly reads 0 until that wiring exists -- wiring only {@link #recordMessageDequeued}
     * without a matching enqueue call site would make it go negative under RoomActorTest's own
     * message sends, which is worse than an honest, if currently unused, zero.
     */
    public void recordMessageEnqueued() {
        mailboxDepth.incrementAndGet();
    }

    public void recordMessageDequeued() {
        mailboxDepth.decrementAndGet();
    }
}
