package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.TickMode;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.RoomStateSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.ManualTime;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 3 verification (plan.md): tick coalescing (ADR-4/§6.2). Uses Pekko's {@link ManualTime}
 * (real {@link ActorTestKit}, not {@code BehaviorTestKit}) because the mechanism under test is
 * genuinely scheduled — a single-shot timer armed against the injected {@link Clock} — and
 * {@code BehaviorTestKit} does not execute timers at all.
 *
 * <p>{@link #clock} and {@link ManualTime} are two independent clocks that must be advanced in
 * lockstep ({@link #advanceTime}): {@code RoomActor} decides flush timing from the injected
 * {@code Clock} (§5.1 rule 4 applies here too — no {@code System.currentTimeMillis()}), while the
 * timer that actually wakes the actor runs on Pekko's scheduler, which {@code ManualTime}
 * replaces for this test. In production both are the real wall clock and move together for free.
 */
class TickCoalescingTest {

    private static final long DURATION_MS = 25_000;
    private static ActorTestKit testKit;
    private static ManualTime manualTime;

    private final AtomicInteger roomSequence = new AtomicInteger();
    private MutableClock clock;
    private ActorRef<RoomActor.Command> room;

    @BeforeAll
    static void initSystem() {
        testKit = ActorTestKit.create(ManualTime.config());
        manualTime = ManualTime.get(testKit.system());
    }

    @AfterAll
    static void shutdownSystem() {
        testKit.shutdownTestKit();
    }

    @AfterEach
    void stopRoom() {
        if (room != null) {
            testKit.stop(room);
        }
    }

    @Test
    void should_rejectFixedTickMode_when_creatingRoomActor_because_onlyCoalesceIsImplemented() {
        assertThatThrownBy(() -> RoomActor.create("room-x", Clock.systemUTC(), fixedScoreCalculator(0),
                new EngineMetrics(new SimpleMeterRegistry()), TickMode.FIXED, testKit.createTestProbe(GameMessage.class).getRef()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectNonZeroMissedStepPolicy_when_creatingRoomActor_because_onlyZeroIsImplemented() {
        // Task 17 / B4: DefinitionLoader already rejects SKIP/ALLOW_LATE at load time -- this is
        // the second fail-fast layer for a caller that bypassed the loader, same shape as tickMode.
        assertThatThrownBy(() -> RoomActor.create("room-x", Clock.systemUTC(), fixedScoreCalculator(0),
                new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE,
                testKit.createTestProbe(GameMessage.class).getRef(),
                NoopRoomSnapshotStore.INSTANCE, 0L, null, MissedStepPolicy.SKIP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_broadcastZeroPackets_when_roomStaysSilentAfterInitialActivity() {
        TestProbe<GameMessage> broadcast = testKit.createTestProbe(GameMessage.class);
        TestProbe<GameMessage> replies = testKit.createTestProbe(GameMessage.class);
        spawnRoom(broadcast.getRef());

        room.tell(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        replies.receiveMessage(); // the direct full-snapshot reply to the joiner
        broadcast.receiveMessage(); // the room-wide delta the join itself dirtied (flushes immediately)

        // Central claim of ADR-4: with nothing left to report, five silent seconds pass with
        // zero outbound packets -- not "packets carrying unchanged state".
        advanceTime(Duration.ofSeconds(5));

        broadcast.expectNoMessage();
    }

    @Test
    void should_capBroadcastDelayAt200ms_when_dirtiedRightAfterAPreviousFlush() {
        TestProbe<GameMessage> broadcast = testKit.createTestProbe(GameMessage.class);
        TestProbe<GameMessage> replies = testKit.createTestProbe(GameMessage.class);
        spawnRoom(broadcast.getRef());

        room.tell(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        replies.receiveMessage();
        broadcast.receiveMessage(); // consumes the join's own immediate flush; lastFlushAt = now

        startGameAndQuestion();
        room.tell(new RoomActor.SubmitAnswer("student-1", 1L, "q-1", List.of("a"), 0L, replies.getRef()));
        replies.receiveMessage(); // ANSWER_ACK -- critical, never coalesced, arrives regardless

        // Dirtied less than 200ms after the last flush: must NOT go out immediately.
        broadcast.expectNoMessage(Duration.ofMillis(50));

        // ...but must go out by the time the 200ms ceiling is reached.
        advanceTime(Duration.ofMillis(200));
        GameMessage delta = broadcast.receiveMessage();
        assertThat(delta.getRoomStateSnapshot().getFull()).isFalse();
    }

    @Test
    void should_includeOnlyChangedPlayers_when_flushingADelta() {
        TestProbe<GameMessage> broadcast = testKit.createTestProbe(GameMessage.class);
        TestProbe<GameMessage> replies = testKit.createTestProbe(GameMessage.class);
        spawnRoom(broadcast.getRef());

        room.tell(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        replies.receiveMessage();
        broadcast.receiveMessage(); // flush #1: only Alice dirty

        // Second join happens at the same virtual instant as flush #1 -- schedules instead of
        // firing immediately, exercising the coalescing window rather than the fast path.
        room.tell(new RoomActor.JoinRoom("student-2", "Bob", replies.getRef()));
        replies.receiveMessage();
        advanceTime(Duration.ofMillis(200));

        GameMessage delta = broadcast.receiveMessage();
        RoomStateSnapshot snapshot = delta.getRoomStateSnapshot();
        assertThat(snapshot.getFull()).isFalse(); // §G2 D1: delta, not full state
        assertThat(snapshot.getPlayersList()).hasSize(1); // only Bob changed -- Alice absent, not zeroed (D3)
        assertThat(snapshot.getPlayers(0).getStudentId()).isEqualTo("student-2");
        assertThat(snapshot.getPlayers(0).getStudentIndex()).isEqualTo(1);
        assertThat(snapshot.getPlayers(0).getDisplayName()).isEqualTo("Bob");
    }

    @Test
    void should_sendFullSnapshot_onlyOnEveryTenthFlush() {
        TestProbe<GameMessage> broadcast = testKit.createTestProbe(GameMessage.class);
        TestProbe<GameMessage> replies = testKit.createTestProbe(GameMessage.class);
        spawnRoom(broadcast.getRef());

        room.tell(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        replies.receiveMessage();
        assertThat(broadcast.receiveMessage().getRoomStateSnapshot().getFull()).isFalse(); // flush #1

        startGameAndQuestion();
        for (long sequence = 1; sequence <= 8; sequence++) {
            room.tell(new RoomActor.SubmitAnswer("student-1", sequence, "q-1", List.of("a"), 0L, replies.getRef()));
            replies.receiveMessage();
            advanceTime(Duration.ofMillis(200));
            GameMessage flush = broadcast.receiveMessage(); // flushes #2..#9
            assertThat(flush.getRoomStateSnapshot().getFull()).isFalse();
        }

        // G2a (tech-design.md §9.1, chốt 2026-09-06): the 10th flush is full, not delta.
        room.tell(new RoomActor.SubmitAnswer("student-1", 9L, "q-1", List.of("a"), 0L, replies.getRef()));
        replies.receiveMessage();
        advanceTime(Duration.ofMillis(200));
        GameMessage tenthFlush = broadcast.receiveMessage();
        assertThat(tenthFlush.getRoomStateSnapshot().getFull()).isTrue();
        assertThat(tenthFlush.getRoomStateSnapshot().getPlayersList()).hasSize(1);
    }

    @Test
    void should_incrementBroadcastSeqMonotonically_acrossJoinAndEveryFlush() {
        // Task 16 / B3: the join's own full snapshot and every later flush (full or delta)
        // must share one unbroken counter -- a client uses gaps in this to detect a dropped
        // broadcast, so it must never restart or skip depending on which snapshot type fired.
        TestProbe<GameMessage> broadcast = testKit.createTestProbe(GameMessage.class);
        TestProbe<GameMessage> replies = testKit.createTestProbe(GameMessage.class);
        spawnRoom(broadcast.getRef());

        room.tell(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        long joinReplySeq = replies.receiveMessage().getRoomStateSnapshot().getBroadcastSeq();
        long firstFlushSeq = broadcast.receiveMessage().getRoomStateSnapshot().getBroadcastSeq();

        startGameAndQuestion();
        room.tell(new RoomActor.SubmitAnswer("student-1", 1L, "q-1", List.of("a"), 0L, replies.getRef()));
        replies.receiveMessage(); // ANSWER_ACK, not a snapshot -- must not consume a broadcast_seq
        advanceTime(Duration.ofMillis(200));
        long secondFlushSeq = broadcast.receiveMessage().getRoomStateSnapshot().getBroadcastSeq();

        assertThat(joinReplySeq).isEqualTo(1L);
        assertThat(firstFlushSeq).isEqualTo(2L);
        assertThat(secondFlushSeq).isEqualTo(3L);
    }

    private void spawnRoom(ActorRef<GameMessage> broadcastTarget) {
        clock = new MutableClock(Instant.parse("2026-09-06T09:00:00Z"));
        room = testKit.spawn(RoomActor.create("room-" + roomSequence.incrementAndGet(), clock,
                fixedScoreCalculator(0), new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE, broadcastTarget));
    }

    private void startGameAndQuestion() {
        room.tell(new RoomActor.StartGame());
        room.tell(new RoomActor.StartQuestion("q-1", DURATION_MS, List.of("a")));
    }

    /** Advances the injected {@link Clock} and Pekko's {@link ManualTime} scheduler together (see class javadoc). */
    private void advanceTime(Duration amount) {
        clock.advanceMillis(amount.toMillis());
        manualTime.timePasses(amount);
    }

    private static ScoreCalculator fixedScoreCalculator(int points) {
        return (answerIds, correctAnswerIds, responseTimeMs) -> points;
    }

    /** Same test double shape as {@code RoomActorTest.MutableClock} -- kept local, no shared production coupling. */
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
