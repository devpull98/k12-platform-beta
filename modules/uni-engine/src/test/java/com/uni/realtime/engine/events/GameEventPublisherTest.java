package com.uni.realtime.engine.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 18 (system-architecture.md §9.3 Rủi ro 6) verification: {@link GameEventPublisher} never
 * blocks its caller regardless of how slow or stuck the {@link GameEventSink} is -- that
 * guarantee is the entire reason this class exists, so it is what every test here proves, not
 * just "events eventually arrive". No real Kafka anywhere (none available in this environment).
 */
class GameEventPublisherTest {

    private GameEventPublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    void should_deliverTheEvent_toTheSink_whenQueueHasRoom() throws InterruptedException {
        RecordingSink sink = new RecordingSink();
        publisher = new GameEventPublisher(sink, 10);
        publisher.start();

        boolean offered = publisher.publish("session-1", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(offered).isTrue();
        assertThat(sink.awaitAtLeast(1, 2)).isTrue();
        assertThat(sink.received.get(0).partitionKey()).isEqualTo("session-1");
    }

    @Test
    void should_returnImmediately_evenWhenTheSinkIsStuck() throws InterruptedException {
        // The core claim: a broker/sink that never returns must not stall the caller (RoomActor
        // in production). Capacity 1 keeps the queue-full case reachable deterministically below.
        BlockingSink sink = new BlockingSink();
        publisher = new GameEventPublisher(sink, 1);
        publisher.start();

        long start = System.nanoTime();
        boolean firstOffered = publisher.publish("session-1", payload());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(firstOffered).isTrue();
        assertThat(elapsedMs).as("publish must return near-instantly, not wait on the sink").isLessThan(500);
        sink.release(); // let the worker finish so tearDown's close() doesn't hang
    }

    @Test
    void should_dropAndReportFalse_whenTheQueueIsFull() throws InterruptedException {
        BlockingSink sink = new BlockingSink();
        publisher = new GameEventPublisher(sink, 1);
        publisher.start();

        // First event: worker picks it up immediately and blocks inside sink.send, draining the
        // queue back to empty. Wait for that hand-off before relying on capacity=1 below.
        publisher.publish("session-1", payload());
        assertThat(sink.awaitEntered(2)).as("worker must have entered send() before we test capacity").isTrue();

        boolean second = publisher.publish("session-2", payload()); // fills the 1-slot queue
        boolean third = publisher.publish("session-3", payload()); // must be dropped

        assertThat(second).isTrue();
        assertThat(third).isFalse();
        assertThat(publisher.droppedEventCount()).isEqualTo(1L);
        sink.release();
    }

    @Test
    void should_swallowASinkException_andKeepProcessingLaterEvents() throws InterruptedException {
        FailOnceThenRecordSink sink = new FailOnceThenRecordSink();
        publisher = new GameEventPublisher(sink, 10);
        publisher.start();

        publisher.publish("session-1", payload()); // this one throws inside the sink
        publisher.publish("session-2", payload()); // must still be processed afterward

        assertThat(sink.awaitAtLeast(1, 2)).isTrue();
        assertThat(sink.received.get(0).partitionKey()).isEqualTo("session-2");
    }

    private static byte[] payload() {
        return "event".getBytes(StandardCharsets.UTF_8);
    }

    private record Received(String partitionKey, byte[] payload) {
    }

    private static final class RecordingSink implements GameEventSink {
        private final List<Received> received = new CopyOnWriteArrayList<>();

        @Override
        public void send(String partitionKey, byte[] payload) {
            received.add(new Received(partitionKey, payload));
        }

        boolean awaitAtLeast(int count, int timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (received.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            return received.size() >= count;
        }

        @Override
        public void close() {
        }
    }

    /** send() blocks until the test calls {@link #release()} -- proves the worker, not the caller, absorbs a stuck sink. */
    private static final class BlockingSink implements GameEventSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch releaseGate = new CountDownLatch(1);

        @Override
        public void send(String partitionKey, byte[] payload) throws InterruptedException {
            entered.countDown();
            releaseGate.await();
        }

        boolean awaitEntered(int timeoutSeconds) throws InterruptedException {
            return entered.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        void release() {
            releaseGate.countDown();
        }

        @Override
        public void close() {
        }
    }

    private static final class FailOnceThenRecordSink implements GameEventSink {
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private volatile boolean thrown = false;

        @Override
        public void send(String partitionKey, byte[] payload) throws Exception {
            if (!thrown) {
                thrown = true;
                throw new RuntimeException("simulated Kafka error");
            }
            received.add(new Received(partitionKey, payload));
        }

        boolean awaitAtLeast(int count, int timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (received.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            return received.size() >= count;
        }

        @Override
        public void close() {
        }
    }
}
