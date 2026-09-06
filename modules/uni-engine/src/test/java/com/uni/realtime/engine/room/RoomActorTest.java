package com.uni.realtime.engine.room;

import com.uni.realtime.engine.metrics.EngineMetrics;
import com.uni.realtime.engine.scoring.FormulaScoreCalculator;
import com.uni.realtime.engine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.RejectReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pekko.actor.testkit.typed.CapturedLogEvent;
import org.apache.pekko.actor.testkit.typed.javadsl.BehaviorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestInbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2 verification (plan.md): FSM, server-authoritative timestamp, two-tier dedupe and
 * the processing watchdog. BehaviorTestKit runs the actor synchronously with no network and
 * no real clock (test-patterns.mdc) — a {@link MutableClock} stands in for the injected
 * {@code Clock} the design mandates (§5.1 rule 4).
 *
 * <p>ScoreCalculator is the real {@link FormulaScoreCalculator#binaryChoice()} (Product decided
 * the formula 2026-09-06 — system-architecture.md §2.5). Every room in this suite is started
 * with {@code correctAnswerIds = List.of("a")} via {@link #startGameAndQuestion}, so a
 * submission of {@code "a"} is genuinely correct and scores 100 -- this test suite is not
 * re-verifying the formula itself (see {@code FormulaScoreCalculatorTest} for that), only that
 * RoomActor wires the question's correct answer through correctly.
 */
class RoomActorTest {

    private static final long DURATION_MS = 25_000;
    private static final List<String> CORRECT_ANSWER = List.of("a");

    private MutableClock clock;
    private BehaviorTestKit<RoomActor.Command> testKit;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-06T09:00:00Z"));
        testKit = BehaviorTestKit.create(
                RoomActor.create("room-101", clock, FormulaScoreCalculator.binaryChoice(), new EngineMetrics(new SimpleMeterRegistry())));
    }

    @Test
    void should_rejectWrongPhase_when_answerSubmittedBeforeGameStarted() {
        TestInbox<GameMessage> inbox = TestInbox.create();

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, inbox.getRef()));

        GameMessage ack = inbox.receiveMessage();
        assertThat(ack.getAnswerAck().getAccepted()).isFalse();
        assertThat(ack.getAnswerAck().getRejectReason()).isEqualTo(RejectReason.WRONG_PHASE);
    }

    @Test
    void should_stopActor_when_gameEndsAfterPlaying() {
        testKit.run(new RoomActor.StartGame());
        assertThat(testKit.isAlive()).isTrue();

        testKit.run(new RoomActor.EndGame());

        assertThat(testKit.isAlive()).isFalse();
    }

    @Test
    void should_stampResponseTimeFromInjectedClock_when_answerAcceptedDuringPlaying() {
        startGameAndQuestion("q-1");
        clock.advanceMillis(250);
        TestInbox<GameMessage> inbox = TestInbox.create();

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, inbox.getRef()));

        var ack = inbox.receiveMessage().getAnswerAck();
        assertThat(ack.getAccepted()).isTrue();
        assertThat(ack.getRejectReason()).isEqualTo(RejectReason.NONE);
        assertThat(ack.getResponseTimeMs()).isEqualTo(250);
        assertThat(ack.getServerReceivedAtMs()).isEqualTo(clock.millis());
        assertThat(ack.getAwardedPoints()).isEqualTo(100);
        assertThat(ack.getTotalScore()).isEqualTo(100);
    }

    @Test
    void should_award0_when_answerDoesNotMatchTheCorrectChoice() {
        startGameAndQuestion("q-1"); // correct answer is "a"
        TestInbox<GameMessage> inbox = TestInbox.create();

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("b"), 0L, inbox.getRef()));

        var ack = inbox.receiveMessage().getAnswerAck();
        assertThat(ack.getAccepted()).isTrue(); // accepted as a valid, on-time submission...
        assertThat(ack.getAwardedPoints()).isZero(); // ...just scored 0, being wrong
        assertThat(ack.getTotalScore()).isZero();
    }

    @Test
    void should_ignoreClientTimestampMs_when_computingScoreAndResponseTime() {
        startGameAndQuestion("q-1");
        clock.advanceMillis(250);

        TestInbox<GameMessage> honestInbox = TestInbox.create();
        TestInbox<GameMessage> forgedInbox = TestInbox.create();

        // Same real server clock, wildly different (forged) client_timestamp_ms.
        testKit.run(new RoomActor.SubmitAnswer(
                "student-honest", 1L, "q-1", List.of("a"), 0L, honestInbox.getRef()));
        testKit.run(new RoomActor.SubmitAnswer(
                "student-cheater", 1L, "q-1", List.of("a"), Long.MAX_VALUE, forgedInbox.getRef()));

        var honestAck = honestInbox.receiveMessage().getAnswerAck();
        var forgedAck = forgedInbox.receiveMessage().getAnswerAck();
        assertThat(forgedAck.getResponseTimeMs()).isEqualTo(honestAck.getResponseTimeMs());
        assertThat(forgedAck.getAwardedPoints()).isEqualTo(honestAck.getAwardedPoints());
    }

    @Test
    void should_rejectPastDeadline_when_submittedAfterDeadlinePlusGrace() {
        startGameAndQuestion("q-1");
        clock.advanceMillis(DURATION_MS + 500 + 1); // deadline + GRACE(500ms) + 1ms
        TestInbox<GameMessage> inbox = TestInbox.create();

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, inbox.getRef()));

        var ack = inbox.receiveMessage().getAnswerAck();
        assertThat(ack.getAccepted()).isFalse();
        assertThat(ack.getRejectReason()).isEqualTo(RejectReason.PAST_DEADLINE);
        assertThat(ack.getAwardedPoints()).isZero();
    }

    @Test
    void should_acceptAnswer_when_submittedExactlyAtDeadlinePlusGrace() {
        startGameAndQuestion("q-1");
        clock.advanceMillis(DURATION_MS + 500); // exactly on the boundary: not PAST it yet
        TestInbox<GameMessage> inbox = TestInbox.create();

        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, inbox.getRef()));

        assertThat(inbox.receiveMessage().getAnswerAck().getAccepted()).isTrue();
    }

    @Test
    void should_resendOriginalAck_when_duplicateSequenceReceived() {
        startGameAndQuestion("q-1");
        clock.advanceMillis(250);
        TestInbox<GameMessage> firstInbox = TestInbox.create();
        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, firstInbox.getRef()));
        GameMessage originalAck = firstInbox.receiveMessage();

        clock.advanceMillis(1_000); // time moves on; a replay must not be re-timed either
        TestInbox<GameMessage> replayInbox = TestInbox.create();
        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, replayInbox.getRef()));

        assertThat(replayInbox.receiveMessage()).isEqualTo(originalAck);

        // A genuinely new sequence afterwards proves the replay above did not double-count.
        TestInbox<GameMessage> nextInbox = TestInbox.create();
        testKit.run(new RoomActor.SubmitAnswer(
                "student-1", 2L, "q-1", List.of("a"), 0L, nextInbox.getRef()));
        assertThat(nextInbox.receiveMessage().getAnswerAck().getTotalScore()).isEqualTo(200);
    }

    @Test
    void should_logWarnAndKeepRunning_when_handlerExceedsWatchdogThreshold() {
        ScoreCalculator slowCalculator = (answerIds, correctAnswerIds, responseTimeMs) -> {
            try {
                Thread.sleep(15); // > WATCHDOG_THRESHOLD_MS(10), well clear of scheduler jitter
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 100;
        };
        BehaviorTestKit<RoomActor.Command> slowTestKit = BehaviorTestKit.create(
                RoomActor.create("room-slow", clock, slowCalculator, new EngineMetrics(new SimpleMeterRegistry())));
        slowTestKit.run(new RoomActor.StartGame());
        slowTestKit.run(new RoomActor.StartQuestion("q-1", DURATION_MS, CORRECT_ANSWER));
        TestInbox<GameMessage> inbox = TestInbox.create();

        slowTestKit.run(new RoomActor.SubmitAnswer(
                "student-1", 1L, "q-1", List.of("a"), 0L, inbox.getRef()));

        assertThat(slowTestKit.isAlive()).isTrue(); // watchdog warns, never interrupts (§10.5)
        List<CapturedLogEvent> warnings = slowTestKit.getAllLogEntries().stream()
                .filter(entry -> entry.level() == Level.WARN)
                .filter(entry -> entry.message().contains("watchdog threshold"))
                .toList();
        assertThat(warnings).isNotEmpty();
    }

    private void startGameAndQuestion(String questionId) {
        testKit.run(new RoomActor.StartGame());
        testKit.run(new RoomActor.StartQuestion(questionId, DURATION_MS, CORRECT_ANSWER));
    }

    /** Stands in for the injected Clock (§5.1 rule 4) so tests control elapsed time exactly. */
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
