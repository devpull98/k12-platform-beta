package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.TickMode;
import com.uni.realtime.gameengine.events.GameEventPublisher;
import com.uni.realtime.gameengine.events.GameEventSink;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.google.protobuf.InvalidProtocolBufferException;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pekko.actor.testkit.typed.javadsl.BehaviorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestInbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 18 verification: {@code RoomActor} publishes a scored {@code SubmitAnswer} outcome
 * through {@link GameEventPublisher}, partitioned by {@code roomId} (a documented stand-in for
 * {@code session_id}, which does not exist in this data model -- see the javadoc on
 * {@code RoomActor.publishGameEvent}). No real Kafka anywhere; {@link GameEventPublisher} itself
 * is already covered in isolation by {@code GameEventPublisherTest}, so this only proves the
 * wiring: the right events, for the right submissions, on the right key.
 */
class RoomActorGameEventTest {

    private static final long DURATION_MS = 25_000;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T09:00:00Z"), ZoneOffset.UTC);

    private GameEventPublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    void should_publishTheAck_partitionedByRoomId_when_anAnswerIsAccepted()
            throws InterruptedException, InvalidProtocolBufferException {
        RecordingSink sink = new RecordingSink();
        publisher = new GameEventPublisher(sink, 10);
        publisher.start();
        BehaviorTestKit<RoomActor.Command> testKit = BehaviorTestKit.create(
                RoomActor.create("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef(), NoopRoomSnapshotStore.INSTANCE, 0L, null,
                        MissedStepPolicy.ZERO, publisher));
        testKit.run(new RoomActor.StartGame());
        testKit.run(new RoomActor.StartQuestion("q-1", DURATION_MS, List.of("a")));

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, TestInbox.<GameMessage>create().getRef()));

        assertThat(sink.awaitAtLeast(1, 2)).isTrue();
        assertThat(sink.received.get(0).partitionKey()).as("roomId stands in for session_id").isEqualTo("room-1");
        GameMessage published = GameMessage.parseFrom(sink.received.get(0).payload());
        AnswerAck ack = published.getAnswerAck();
        assertThat(ack.getAccepted()).isTrue();
        assertThat(ack.getAwardedPoints()).isEqualTo(100);
    }

    @Test
    void should_notPublish_when_theAnswerIsRejected() throws InterruptedException {
        RecordingSink sink = new RecordingSink();
        publisher = new GameEventPublisher(sink, 10);
        publisher.start();
        BehaviorTestKit<RoomActor.Command> testKit = BehaviorTestKit.create(
                RoomActor.create("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef(), NoopRoomSnapshotStore.INSTANCE, 0L, null,
                        MissedStepPolicy.ZERO, publisher));
        // No StartGame/StartQuestion -- WRONG_PHASE rejection, nothing worth shipping to analytics.

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, TestInbox.<GameMessage>create().getRef()));

        Thread.sleep(100); // give a wrongly-firing publish a chance to land before asserting absence
        assertThat(sink.received).isEmpty();
    }

    @Test
    void should_notThrow_when_noPublisherWasWired() {
        BehaviorTestKit<RoomActor.Command> testKit = BehaviorTestKit.create(
                RoomActor.create("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef())); // six-arg overload -- publisher defaults to null
        testKit.run(new RoomActor.StartGame());
        testKit.run(new RoomActor.StartQuestion("q-1", DURATION_MS, List.of("a")));

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, TestInbox.<GameMessage>create().getRef()));
        // No assertion needed beyond "did not throw" -- a null gameEventPublisher must be silently skippable.
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
}
