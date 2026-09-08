package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.TickMode;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pekko.actor.testkit.typed.javadsl.BehaviorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestInbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leave-room flow (§3 note: "luồng rời phòng chưa có tín hiệu nào từ Gateway sang Engine") and
 * {@code TeacherCommand.KICK_STUDENT} (Task 13's "cố ý chưa làm") verification.
 *
 * <p>{@link BehaviorTestKit} (synchronous, never runs Pekko timers -- see {@code TickCoalescingTest}'s
 * own javadoc) is enough here: {@link #clock} is advanced past the 200ms coalescing window
 * ({@code MIN_FLUSH_INTERVAL_MS}) BEFORE the action under test, so {@code scheduleFlushIfDirty()}
 * always takes its synchronous "flush right now" branch, never the "arm a timer" branch a real
 * {@code ActorTestKit}/{@code ManualTime} would be needed for.
 */
class RoomActorPresenceTest {

    private MutableClock clock;
    private TestInbox<GameMessage> broadcast;
    private BehaviorTestKit<RoomActor.Command> testKit;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-07T09:00:00Z"));
        broadcast = TestInbox.create();
        testKit = BehaviorTestKit.create(
                RoomActor.create("room-101", clock, FormulaScoreCalculator.binaryChoice(),
                        new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE, broadcast.getRef()));
    }

    @Test
    void should_markPlayerDisconnected_when_studentDisconnectedReceived() {
        TestInbox<GameMessage> replies = TestInbox.create();
        testKit.run(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        replies.receiveMessage();
        broadcast.receiveMessage(); // the join's own immediate flush -- room is clean again
        clock.advanceMillis(300); // clear of the 200ms coalescing window (§6.2)

        testKit.run(new RoomActor.StudentDisconnected("student-1"));

        GameMessage delta = broadcast.receiveMessage();
        var players = delta.getRoomStateSnapshot().getPlayersList();
        assertThat(players).hasSize(1);
        assertThat(players.get(0).getStudentId()).isEqualTo("student-1");
        assertThat(players.get(0).getConnected()).isFalse();
    }

    @Test
    void should_beNoOp_when_studentDisconnectedForAStudentNeverJoined() {
        testKit.run(new RoomActor.StudentDisconnected("student-ghost"));

        // No roster entry to dirty -- the room must stay clean, not just "not crash".
        assertThat(broadcast.hasMessages()).isFalse();
    }

    @Test
    void should_broadcastStudentKickedImmediately_bypassingCoalescing_when_kickStudentReceived() {
        TestInbox<GameMessage> replies = TestInbox.create();
        testKit.run(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        replies.receiveMessage();
        broadcast.receiveMessage(); // the join's own flush -- room is clean again
        // Deliberately NOT advancing the clock past the 200ms window here: the point of this
        // test is that STUDENT_KICKED goes out regardless, the same reason ANSWER_ACK does.

        testKit.run(new RoomActor.KickStudent("student-1"));

        GameMessage kicked = broadcast.receiveMessage();
        assertThat(kicked.getType()).isEqualTo(MessageType.STUDENT_KICKED);
        assertThat(kicked.getRoomId()).isEqualTo("room-101");
        assertThat(kicked.getStudentId()).isEqualTo("student-1");
    }

    @Test
    void should_alsoMarkPlayerDisconnected_when_kickStudentReceived() {
        TestInbox<GameMessage> replies = TestInbox.create();
        testKit.run(new RoomActor.JoinRoom("student-1", "Alice", replies.getRef()));
        replies.receiveMessage();
        broadcast.receiveMessage();
        clock.advanceMillis(300); // clear of the 200ms coalescing window, so the roster change
                                  // below also flushes synchronously, right after the kick notice

        testKit.run(new RoomActor.KickStudent("student-1"));

        GameMessage kicked = broadcast.receiveMessage(); // the immediate notice, sent first
        assertThat(kicked.getType()).isEqualTo(MessageType.STUDENT_KICKED);
        GameMessage delta = broadcast.receiveMessage(); // the roster update, sent right after
        var players = delta.getRoomStateSnapshot().getPlayersList();
        assertThat(players).hasSize(1);
        assertThat(players.get(0).getStudentId()).isEqualTo("student-1");
        assertThat(players.get(0).getConnected()).isFalse();
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
