package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 17 (B4): {@code missedStepPolicy} must be applied on purpose, not just happen to look
 * right because an unscored student defaults to 0 (see {@code _context.md} B4 and
 * {@code RoomState#applyMissedStepPolicy}'s javadoc). These tests exercise
 * {@link RoomState#missedStepsFor} directly -- the signal that only exists because this task
 * added a real branch, not an accident.
 */
class RoomStateMissedStepPolicyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void should_recordZeroMissedSteps_whenJoiningBeforeAnyQuestionStarted() {
        RoomState state = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());

        state.joinRoom("student-1", "Alice");

        assertThat(state.missedStepsFor("student-1")).isZero();
    }

    @Test
    void should_recordMissedSteps_whenJoiningAfterQuestionsHaveAlreadyStarted() {
        RoomState state = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        state.startGame();
        state.startQuestion("q-1", 25_000, List.of("a"));
        state.startQuestion("q-2", 25_000, List.of("a"));

        state.joinRoom("late-student", "Late Joiner");

        assertThat(state.missedStepsFor("late-student"))
                .as("joined after 2 questions had already started")
                .isEqualTo(2);
    }

    @Test
    void should_notRecomputeMissedSteps_onReconnect() {
        // §4.8: reconnect and late-join are two entirely different flows -- a reconnecting
        // student must keep whatever missedStepsAtJoin their REAL first join recorded.
        RoomState state = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        state.startGame();
        state.startQuestion("q-1", 25_000, List.of("a"));
        state.joinRoom("student-1", "Alice"); // missed 1 step

        state.startQuestion("q-2", 25_000, List.of("a"));
        state.joinRoom("student-1", "Alice"); // reconnect, not a new join

        assertThat(state.missedStepsFor("student-1"))
                .as("reconnect must not re-capture questionsStartedCount")
                .isEqualTo(1);
    }

    @Test
    void should_scoreZeroPoints_forStepsMissedBeforeLateJoin() {
        RoomState state = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        state.startGame();
        state.startQuestion("q-1", 25_000, List.of("a"));

        var fullSnapshot = state.joinRoom("late-student", "Late Joiner").getRoomStateSnapshot();

        var lateStudent = fullSnapshot.getPlayersList().stream()
                .filter(p -> p.getStudentId().equals("late-student"))
                .findFirst().orElseThrow();
        assertThat(lateStudent.getScore()).as("no way to answer a step that already ended").isZero();
        assertThat(state.missedStepsFor("late-student")).isEqualTo(1);
    }

    @Test
    void should_surviveRestore_soALateJoinerAfterAPodHandoffIsStillCountedCorrectly() {
        RoomState original = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        original.startGame();
        original.startQuestion("q-1", 25_000, List.of("a"));
        original.startQuestion("q-2", 25_000, List.of("a"));
        original.startQuestion("q-3", 25_000, List.of("a"));

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                original.serializeSnapshot());
        restored.joinRoom("late-after-handoff", "Post-Handoff Joiner");

        assertThat(restored.missedStepsFor("late-after-handoff"))
                .as("questionsStartedCount must not reset to 0 on restore")
                .isEqualTo(3);
    }

    @Test
    void should_preserveMissedStepsAtJoin_acrossRestore() {
        RoomState original = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        original.startGame();
        original.startQuestion("q-1", 25_000, List.of("a"));
        original.joinRoom("student-1", "Alice"); // missed 1 step, before restore

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                original.serializeSnapshot());

        assertThat(restored.missedStepsFor("student-1")).isEqualTo(1);
    }

    @Test
    void should_throwIllegalStateException_whenANonZeroPolicyReachesALateJoin() {
        // Simulates a caller bypassing BOTH DefinitionLoader and RoomActor.create's fail-fast
        // gates (neither of which this test goes through) -- RoomState must not silently do
        // nothing with a policy it does not implement.
        RoomState state = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(), MissedStepPolicy.SKIP);
        state.startGame();
        state.startQuestion("q-1", 25_000, List.of("a"));

        assertThatThrownBy(() -> state.joinRoom("late-student", "Late Joiner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SKIP");
    }

    @Test
    void should_notThrow_whenANonZeroPolicyIsConfigured_butNoOneJoinsLate() {
        // The guard only fires when there is actually something to apply the policy to -- a
        // room that never has a late joiner must not pay for a policy it never exercises.
        RoomState state = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(), MissedStepPolicy.SKIP);

        state.joinRoom("student-1", "Alice"); // joins before any question -- not late

        assertThat(state.missedStepsFor("student-1")).isZero();
    }
}
