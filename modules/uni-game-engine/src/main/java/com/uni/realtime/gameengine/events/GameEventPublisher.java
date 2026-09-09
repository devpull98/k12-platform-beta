package com.uni.realtime.gameengine.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

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

    public boolean publish(String partitionKey, byte[] payload) {
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
