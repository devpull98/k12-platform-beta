package com.uni.realtime.engine.room;

import com.uni.realtime.engine.definition.TickMode;
import com.uni.realtime.engine.metrics.EngineMetrics;
import com.uni.realtime.engine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pekko.actor.testkit.typed.javadsl.BehaviorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestInbox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 (Hot Snapshot) verification: {@code RoomActor} triggers
 * {@link RoomSnapshotStore#save} async, off the coalescing flush, without ever waiting on it —
 * and can resume from a previously saved snapshot. {@code BehaviorTestKit} keeps this
 * synchronous and network-free (test-patterns.mdc); the fake store below is deliberately
 * synchronous too (returns already-completed futures) so assertions do not race a real thread.
 */
class RoomActorSnapshotTest {

    private static final long DURATION_MS = 25_000;

    @Test
    void should_saveASnapshot_when_theFirstDirtyFlushHappens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-07T09:00:00Z"));
        RecordingSnapshotStore store = new RecordingSnapshotStore();
        BehaviorTestKit<RoomActor.Command> testKit = BehaviorTestKit.create(
                RoomActor.create("room-1", clock, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef(), store, 5L, null));

        testKit.run(new RoomActor.JoinRoom("student-1", "Alice", TestInbox.<GameMessage>create().getRef()));

        assertThat(store.saveCalls.get()).isEqualTo(1);
        assertThat(store.lastEpoch.get()).isEqualTo(5L);
    }

    @Test
    void should_notSaveAgain_when_anotherFlushHappensWithinTheMinInterval() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-07T09:00:00Z"));
        RecordingSnapshotStore store = new RecordingSnapshotStore();
        BehaviorTestKit<RoomActor.Command> testKit = BehaviorTestKit.create(
                RoomActor.create("room-1", clock, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef(), store, 0L, null));
        testKit.run(new RoomActor.JoinRoom("student-1", "Alice", TestInbox.<GameMessage>create().getRef()));
        assertThat(store.saveCalls.get()).isEqualTo(1);

        clock.advanceMillis(500); // well under SNAPSHOT_MIN_INTERVAL_MS(2000), but >= flush's own 200ms
        testKit.run(new RoomActor.JoinRoom("student-2", "Bob", TestInbox.<GameMessage>create().getRef()));

        assertThat(store.saveCalls.get()).as("must not re-snapshot faster than the 2s floor").isEqualTo(1);
    }

    @Test
    void should_saveAgain_when_enoughTimeHasPassedSinceTheLastSnapshot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-07T09:00:00Z"));
        RecordingSnapshotStore store = new RecordingSnapshotStore();
        BehaviorTestKit<RoomActor.Command> testKit = BehaviorTestKit.create(
                RoomActor.create("room-1", clock, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef(), store, 0L, null));
        testKit.run(new RoomActor.JoinRoom("student-1", "Alice", TestInbox.<GameMessage>create().getRef()));
        assertThat(store.saveCalls.get()).isEqualTo(1);

        clock.advanceMillis(2_001);
        testKit.run(new RoomActor.JoinRoom("student-2", "Bob", TestInbox.<GameMessage>create().getRef()));

        assertThat(store.saveCalls.get()).isEqualTo(2);
    }

    @Test
    void should_neverBlockOnASlowOrFailingStore() {
        // The whole point of the design: a store whose future never resolves must not stop the
        // actor from processing the next message.
        MutableClock clock = new MutableClock(Instant.parse("2026-09-07T09:00:00Z"));
        BehaviorTestKit<RoomActor.Command> testKit = BehaviorTestKit.create(
                RoomActor.create("room-1", clock, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef(), new NeverCompletingSnapshotStore(), 0L, null));

        testKit.run(new RoomActor.JoinRoom("student-1", "Alice", TestInbox.<GameMessage>create().getRef()));
        TestInbox<GameMessage> secondInbox = TestInbox.create();
        testKit.run(new RoomActor.JoinRoom("student-2", "Bob", secondInbox.getRef()));

        assertThat(secondInbox.hasMessages()).as("a hung snapshot write must not stall message handling").isTrue();
    }

    @Test
    void should_resumeScoreAndRoster_when_createdFromASnapshot() {
        RoomState original = new RoomState("room-1", Clock.systemUTC(), FormulaScoreCalculator.binaryChoice());
        original.joinRoom("student-1", "Alice");
        original.startGame();
        original.startQuestion("q-1", DURATION_MS, List.of("a"));
        original.submitAnswer("student-1", 1L, "q-1", List.of("a")); // +100
        byte[] snapshotBytes = original.serializeSnapshot();

        MutableClock clock = new MutableClock(Instant.parse("2026-09-07T09:00:00Z"));
        BehaviorTestKit<RoomActor.Command> restored = BehaviorTestKit.create(
                RoomActor.create("room-1", clock, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                        TestInbox.<GameMessage>create().getRef(), new RecordingSnapshotStore(), 1L, snapshotBytes));
        TestInbox<GameMessage> joinInbox = TestInbox.create();

        restored.run(new RoomActor.JoinRoom("student-2", "Bob", joinInbox.getRef()));

        var alice = joinInbox.receiveMessage().getRoomStateSnapshot().getPlayersList().stream()
                .filter(p -> p.getStudentId().equals("student-1"))
                .findFirst().orElseThrow();
        assertThat(alice.getScore()).as("restored room must resume with the persisted score").isEqualTo(100);
    }

    private static final class RecordingSnapshotStore implements RoomSnapshotStore {
        private final AtomicInteger saveCalls = new AtomicInteger();
        private final AtomicReference<Long> lastEpoch = new AtomicReference<>();

        @Override
        public CompletableFuture<Boolean> save(String roomId, long epoch, byte[] envelopeBytes) {
            saveCalls.incrementAndGet();
            lastEpoch.set(epoch);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Optional<byte[]>> load(String roomId) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private static final class NeverCompletingSnapshotStore implements RoomSnapshotStore {
        @Override
        public CompletableFuture<Boolean> save(String roomId, long epoch, byte[] envelopeBytes) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Optional<byte[]>> load(String roomId) {
            return new CompletableFuture<>();
        }
    }

    /** Stands in for the injected Clock (§5.1 rule 4), same shape as RoomActorTest's. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("not needed by this test double");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
