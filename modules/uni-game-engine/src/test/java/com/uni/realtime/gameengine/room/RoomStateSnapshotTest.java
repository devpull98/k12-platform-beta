package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.RejectReason;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 (Hot Snapshot) verification: {@link RoomState#serializeSnapshot()} /
 * {@link RoomState#restore} round-trip, and the {@link RoomState#submitAnswer} dedupe fix a
 * restored room depends on (a room rebuilt from a snapshot has {@code lastSeenSequence} but no
 * ack history -- see the comment in {@code submitAnswer}). Pure logic, no actor, no external store.
 */
class RoomStateSnapshotTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void should_restoreAnEmptyRoom_asEmpty() {
        RoomState original = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                original.serializeSnapshot());

        assertThat(restored.phase()).isEqualTo(GamePhase.LOBBY);
        assertThat(restored.isDirty()).as("a restored room has nothing pending to broadcast").isFalse();
    }

    @Test
    void should_restoreRosterScoreAndPhase_afterAFullGameFlow() {
        RoomState original = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        original.joinRoom("student-1", "Alice");
        original.joinRoom("student-2", "Bob");
        original.startGame();
        original.startQuestion("q-1", 25_000, List.of("a"));
        original.submitAnswer("student-1", 1L, "q-1", List.of("a")); // correct, +100
        original.submitAnswer("student-2", 1L, "q-1", List.of("b")); // wrong, +0

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                original.serializeSnapshot());

        assertThat(restored.phase()).isEqualTo(GamePhase.PLAYING);
        // joinRoom always returns a full snapshot (§6.2), regardless of dirty state -- the
        // simplest way to inspect every restored player's score in one assertion.
        var fullSnapshot = restored.joinRoom("student-3", "Carol").getRoomStateSnapshot();
        assertThat(fullSnapshot.getPlayersList())
                .extracting(p -> p.getStudentId(), p -> p.getScore())
                .contains(
                        org.assertj.core.groups.Tuple.tuple("student-1", 100),
                        org.assertj.core.groups.Tuple.tuple("student-2", 0));
    }

    @Test
    void should_preserveStudentIndex_acrossRestore() {
        // §G2 D4: student_index must stay stable, and a newly restored room must not
        // re-assign indices it already handed out before the snapshot was taken.
        RoomState original = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        original.joinRoom("student-1", "Alice");
        original.joinRoom("student-2", "Bob");

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                original.serializeSnapshot());
        var snapshot = restored.joinRoom("student-3", "Carol").getRoomStateSnapshot();

        var carol = snapshot.getPlayersList().stream()
                .filter(p -> p.getStudentId().equals("student-3"))
                .findFirst().orElseThrow();
        assertThat(carol.getStudentIndex()).as("must not collide with 0/1 already assigned before restore").isEqualTo(2);
    }

    @Test
    void should_rejectDuplicateSequence_withoutRescoring_afterRestoreLostTheAckHistory() {
        // The exact bug Task 14 had to fix in RoomState.submitAnswer: lastSeenSequence survives
        // a restore, lastAckByStudent deliberately does not (§4.8 size budget).
        RoomState original = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        original.joinRoom("student-1", "Alice");
        original.startGame();
        original.startQuestion("q-1", 25_000, List.of("a"));
        original.submitAnswer("student-1", 1L, "q-1", List.of("a")); // +100, lastSeenSequence[student-1] = 1

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                original.serializeSnapshot());
        // Restored room has no currentQuestionId echo needed here -- resubmit the SAME sequence.
        restored.startQuestion("q-1", 25_000, List.of("a")); // re-open the question window for this test
        GameMessage replay = restored.submitAnswer("student-1", 1L, "q-1", List.of("a"));

        assertThat(replay.getAnswerAck().getAccepted()).isFalse();
        assertThat(replay.getAnswerAck().getRejectReason()).isEqualTo(RejectReason.DUPLICATE_SEQUENCE);
        assertThat(replay.getAnswerAck().getAwardedPoints()).as("must not re-score a duplicate").isZero();
        assertThat(replay.getAnswerAck().getTotalScore()).as("score must stay at the restored value, not double").isEqualTo(100);
    }

    @Test
    void should_continueBroadcastSeq_notResetItToZero_afterRestore() {
        // Task 16 / B3: broadcast_seq must survive a pod handoff, or the first broadcast after
        // restore would look like a rewind to any client still tracking the pre-restore count.
        RoomState original = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        original.joinRoom("student-1", "Alice"); // broadcast_seq -> 1
        original.joinRoom("student-2", "Bob"); // broadcast_seq -> 2

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                original.serializeSnapshot());
        long nextSeq = restored.joinRoom("student-3", "Carol").getRoomStateSnapshot().getBroadcastSeq();

        assertThat(nextSeq).as("must continue from 2, not restart at 1").isEqualTo(3L);
    }

    @Test
    void should_stayUnderFiveKilobytes_forARealisticFullRoom() {
        RoomState state = new RoomState("room-large", CLOCK, FormulaScoreCalculator.binaryChoice());
        state.startGame();
        state.startQuestion("question-with-a-reasonably-long-identifier-123", 25_000, List.of("a", "b", "c"));
        for (int i = 0; i < 12; i++) { // §1.1: a room holds 12 students in Phase 1
            String studentId = "student-" + i;
            state.joinRoom(studentId, "A Reasonably Long Display Name " + i);
            state.submitAnswer(studentId, 1L, "question-with-a-reasonably-long-identifier-123", List.of("a"));
        }

        byte[] payload = state.serializeSnapshot();
        var wrapped = SnapshotEnvelope.wrap(1L, payload);

        assertThat(wrapped).as("a realistic 12-player room must fit the < 5 KB Hot Snapshot budget").isPresent();
        assertThat(wrapped.get().length).isLessThan(5 * 1024);
    }
}
