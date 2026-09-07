package com.uni.realtime.gameengine.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Task 18 (system-architecture.md §9.3 Rủi ro 6): the isolation layer between {@code RoomActor}
 * and Kafka. {@code RoomActor} must never call a Kafka producer directly -- if the broker lags
 * and the producer's own buffer fills, a default-configured {@code send()} blocks for up to
 * {@code max.block.ms} (60s default), which on the actor's own dispatcher thread freezes every
 * room sharing it, not just the one that tried to publish.
 *
 * <p>{@link #publish} runs on the caller's thread (the actor dispatcher) and does exactly one
 * thing there: a non-blocking {@link BlockingQueue#offer}. A full queue drops the event and
 * returns {@code false} -- analytics/audit data loss under sustained overload, which §9.3
 * explicitly prefers over blocking the game itself. The actual Kafka call
 * ({@link GameEventSink#send}) happens only on {@link #worker}, a single dedicated thread that
 * never touches actor state.
 */
public final class GameEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameEventPublisher.class);

    private record Event(String partitionKey, byte[] payload) {
    }

    private final BlockingQueue<Event> queue;
    private final GameEventSink sink;
    private final Thread worker;
    private final AtomicLong droppedEvents = new AtomicLong();
    private volatile boolean running = true;

    public GameEventPublisher(GameEventSink sink, int queueCapacity) {
        this.sink = sink;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::runLoop, "game-event-publisher");
        this.worker.setDaemon(true);
    }

    public void start() {
        worker.start();
    }

    /**
     * Never blocks and never throws -- the one contract {@code RoomActor} is allowed to depend
     * on. Returns {@code false} if the event was dropped (queue full), purely for tests and
     * metrics; callers on the hot path have nothing useful to do with a {@code false} beyond
     * that, since retrying synchronously would reintroduce the exact blocking this class exists
     * to prevent.
     */
    public boolean publish(String partitionKey, byte[] payload) {
        // Prove-it (2026-09-07): hardcoding offered=true here turned exactly the
        // queue-full-drop test red -- confirms it exercises this return value, not the offer call.
        boolean offered = queue.offer(new Event(partitionKey, payload));
        if (!offered) {
            long droppedTotal = droppedEvents.incrementAndGet();
            log.warn("game event queue full -- dropped event for key {} ({} dropped total)", partitionKey, droppedTotal);
        }
        return offered;
    }

    public long droppedEventCount() {
        return droppedEvents.get();
    }

    private void runLoop() {
        while (running) {
            Event event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                sink.send(event.partitionKey(), event.payload());
            } catch (Exception e) {
                log.warn("failed to publish game event for key {}", event.partitionKey(), e);
            }
        }
    }

    /** Stops the worker and closes the sink. Best-effort: queued events not yet sent are dropped, not flushed. */
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(2).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            sink.close();
        } catch (Exception e) {
            log.warn("failed to close game event sink cleanly", e);
        }
    }
}
