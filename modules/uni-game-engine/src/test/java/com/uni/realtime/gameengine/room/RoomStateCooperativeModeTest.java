package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.RoomStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Task 21 (INCLASS-GAME-001-v2.1, BDD: cooperative-boss.feature) verification: room-wide
 * progress tracking for {@code GAME_MODE_COOPERATIVE} -- the whole room shares one progress
 * counter, incremented on any student's correct answer, independent of per-student score.
 */
class RoomStateCooperativeModeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T09:00:00Z"), ZoneOffset.UTC);
    private static final List<ProgressStage> STAGES = List.of(
            new ProgressStage(0, "boss-0.svg"),
            new ProgressStage(30, "boss-30.svg"),
            new ProgressStage(70, "boss-70.svg"),
            new ProgressStage(100, "boss-100.svg"));

    @Test
    void should_incrementRoomProgress_forEveryCorrectAnswer_regardlessOfWhichStudentAnswered() {
        RoomState room = cooperativeRoom(10, STAGES, SharedResourceType.NONE, 0);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-02", "S2");
        room.joinRoom("student-03", "S3");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));

        room.submitAnswer("student-01", 1L, "q-1", List.of("a"));
        room.submitAnswer("student-02", 1L, "q-1", List.of("a"));
        room.submitAnswer("student-03", 1L, "q-1", List.of("a"));

        RoomStateSnapshot snapshot = room.flush().getRoomStateSnapshot();
        assertThat(snapshot.getProgress().getCurrentProgress()).isEqualTo(3);
        assertThat(snapshot.getProgress().getProgressPercentage()).isEqualTo(30);
        assertThat(snapshot.getProgress().getStageIndex()).isEqualTo(1);
        assertThat(snapshot.getGameMode()).isEqualTo(GameMode.GAME_MODE_COOPERATIVE);
    }

    @Test
    void should_floorThePercentage_ratherThanRound() {
        // 1/3 = 33.33...% -- INCLASS-GAME-001-v2.1 §5.6 luật biên 6: floor, never round.
        RoomState room = cooperativeRoom(3, List.of(), SharedResourceType.NONE, 0);
        room.joinRoom("student-01", "S1");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));

        room.submitAnswer("student-01", 1L, "q-1", List.of("a"));

        assertThat(room.flush().getRoomStateSnapshot().getProgress().getProgressPercentage()).isEqualTo(33);
    }

    @Test
    void should_transitionToFinished_when_progressReachesTarget() {
        RoomState room = cooperativeRoom(2, STAGES, SharedResourceType.NONE, 0);
        room.joinRoom("student-01", "S1");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a"));
        assertThat(room.phase()).isEqualTo(GamePhase.PLAYING);

        room.startQuestion("q-2", 25_000, List.of("b"));
        room.submitAnswer("student-01", 2L, "q-2", List.of("b"));

        assertThat(room.phase()).isEqualTo(GamePhase.FINISHED);
        assertThat(room.flush().getRoomStateSnapshot().getProgress().getProgressPercentage()).isEqualTo(100);
    }

    @Test
    void should_deductSharedResourceTime_when_answerIsWrong() {
        RoomState room = cooperativeRoom(10, List.of(), SharedResourceType.TIME, 5);
        room.joinRoom("student-05", "S5");
        room.startGame();
        room.startQuestion("q-1", 30_000, List.of("a"));
        long deadlineBefore = room.flush().getRoomStateSnapshot().getDeadlineMs();

        room.submitAnswer("student-05", 1L, "q-1", List.of("wrong-choice"));

        long deadlineAfter = room.flush().getRoomStateSnapshot().getDeadlineMs();
        assertThat(deadlineAfter).isEqualTo(deadlineBefore - 5_000);
        assertThat(room.flush().getRoomStateSnapshot().getProgress().getCurrentProgress())
                .as("a wrong answer must not advance progress").isZero();
    }

    @Test
    void should_notDeductAnything_when_sharedResourceIsNone() {
        RoomState room = cooperativeRoom(10, List.of(), SharedResourceType.NONE, 0);
        room.joinRoom("student-05", "S5");
        room.startGame();
        room.startQuestion("q-1", 30_000, List.of("a"));
        long deadlineBefore = room.flush().getRoomStateSnapshot().getDeadlineMs();

        room.submitAnswer("student-05", 1L, "q-1", List.of("wrong-choice"));

        assertThat(room.flush().getRoomStateSnapshot().getDeadlineMs()).isEqualTo(deadlineBefore);
    }

    @Test
    void should_surviveRestore_keepingAccumulatedProgress() {
        RoomState original = cooperativeRoom(10, STAGES, SharedResourceType.NONE, 0);
        original.joinRoom("student-01", "S1");
        original.startGame();
        original.startQuestion("q-1", 25_000, List.of("a"));
        original.submitAnswer("student-01", 1L, "q-1", List.of("a"));

        RoomState restored = RoomState.restore("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                com.uni.realtime.gameengine.definition.MissedStepPolicy.ZERO, GameMode.GAME_MODE_COOPERATIVE,
                10, STAGES, SharedResourceType.NONE, 0, original.serializeSnapshot());

        assertThat(restored.joinRoom("student-02", "S2").getRoomStateSnapshot().getProgress().getCurrentProgress())
                .isEqualTo(1);
    }

    @Test
    void should_leaveGameModeAtSolo_when_usingThePhase1Constructor() {
        // Backward-compat sanity: the pre-existing 3-arg constructor must not silently start
        // behaving like a cooperative room.
        RoomState room = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        room.joinRoom("student-01", "S1");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a"));

        assertThat(room.flush().getRoomStateSnapshot().getGameMode()).isEqualTo(GameMode.GAME_MODE_SOLO);
    }

    private static RoomState cooperativeRoom(int progressTarget, List<ProgressStage> stages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty) {
        return new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(),
                com.uni.realtime.gameengine.definition.MissedStepPolicy.ZERO, GameMode.GAME_MODE_COOPERATIVE,
                progressTarget, stages, sharedResourceType, sharedResourcePenalty);
    }
}
