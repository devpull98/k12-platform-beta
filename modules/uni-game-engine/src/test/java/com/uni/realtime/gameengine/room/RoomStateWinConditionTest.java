package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.TeamAssignment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Task 23 verification: {@code GameOver} content (reason/winner_id) for every win condition,
 * and that {@link RoomState#endGame()} never overwrites a reason a win condition already set.
 */
class RoomStateWinConditionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T09:00:00Z"), ZoneOffset.UTC);
    private static final List<TeamAssignment> TWO_TEAMS = List.of(
            team("A", "student-01", "student-02"), team("B", "student-03", "student-04"));

    @Test
    void should_setProgressCompletedReason_withNoWinnerId_whenCooperativeRoomFinishes() {
        RoomState room = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(), MissedStepPolicy.ZERO,
                GameMode.GAME_MODE_COOPERATIVE, 1, List.<ProgressStage>of(), SharedResourceType.NONE, 0);
        room.joinRoom("student-01", "S1");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a"));

        GameMessage gameOver = room.buildGameOver();
        assertThat(gameOver.getGameOver().getReason()).isEqualTo("progress_completed");
        assertThat(gameOver.getGameOver().getWinnerId()).isEmpty();
    }

    @Test
    void should_setFirstToFinishReasonAndWinnerTeamId_whenATeamFinishesFirst() {
        RoomState room = teamRoom(TWO_TEAMS, 1, WinCondition.FIRST_TO_FINISH);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-03", "S3");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));

        room.submitAnswer("student-01", 1L, "q-1", List.of("a")); // Team A reaches target=1 first

        assertThat(room.phase()).isEqualTo(GamePhase.FINISHED);
        GameMessage gameOver = room.buildGameOver();
        assertThat(gameOver.getGameOver().getReason()).isEqualTo("first_to_finish");
        assertThat(gameOver.getGameOver().getWinnerId()).isEqualTo("A");
    }

    @Test
    void should_stopTheActorFromDoubleFinishing_whenTheLosingTeamAnswersAfterward() {
        // Structural guarantee (single-threaded mailbox): once FINISHED, a later correct answer
        // for the OTHER team must not overwrite the winner.
        RoomState room = teamRoom(TWO_TEAMS, 1, WinCondition.FIRST_TO_FINISH);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-03", "S3");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a")); // Team A wins

        GameMessage lateAnswer = room.submitAnswer("student-03", 1L, "q-1", List.of("a"));

        assertThat(lateAnswer.getAnswerAck().getAccepted())
                .as("room is FINISHED, WRONG_PHASE must reject this before it reaches team logic").isFalse();
        assertThat(room.buildGameOver().getGameOver().getWinnerId()).isEqualTo("A");
    }

    @Test
    void should_computeHighestScoringTeam_whenEndGameFiresUnderMostPointsWhenTimeUp() {
        RoomState room = teamRoom(TWO_TEAMS, 100, WinCondition.MOST_POINTS_WHEN_TIME_UP);
        room.joinRoom("student-01", "S1"); // Team A
        room.joinRoom("student-03", "S3"); // Team B
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a")); // Team A: +100
        room.submitAnswer("student-03", 1L, "q-1", List.of("wrong")); // Team B: +0

        room.endGame(); // simulates a teacher/timer ending the round before progress_target is hit

        GameMessage gameOver = room.buildGameOver();
        assertThat(gameOver.getGameOver().getReason()).isEqualTo("most_points_when_time_up");
        assertThat(gameOver.getGameOver().getWinnerId()).isEqualTo("A");
    }

    @Test
    void should_leaveWinnerIdEmpty_whenMostPointsWhenTimeUpEndsInATie() {
        RoomState room = teamRoom(TWO_TEAMS, 100, WinCondition.MOST_POINTS_WHEN_TIME_UP);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-03", "S3");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a")); // Team A: +100
        room.submitAnswer("student-03", 1L, "q-1", List.of("a")); // Team B: +100 (tied)

        room.endGame();

        assertThat(room.buildGameOver().getGameOver().getWinnerId()).isEmpty();
    }

    @Test
    void should_useTeacherEndedReason_forASoloRoomEndedManually() {
        RoomState room = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        room.joinRoom("student-01", "S1");

        room.endGame();

        GameMessage gameOver = room.buildGameOver();
        assertThat(gameOver.getGameOver().getReason()).isEqualTo("teacher_ended");
        assertThat(gameOver.getGameOver().getWinnerId()).isEmpty();
        assertThat(gameOver.getGameOver().getFinalStandingsList()).hasSize(1);
    }

    @Test
    void should_notOverwriteWinConditionReason_when_endGameIsCalledAfterAnAutomaticFinish() {
        RoomState room = teamRoom(TWO_TEAMS, 1, WinCondition.FIRST_TO_FINISH);
        room.joinRoom("student-01", "S1");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a")); // auto-finish via first_to_finish

        room.endGame(); // e.g. RoomActor still forwards a stray/late TeacherCommand.END_GAME

        GameMessage gameOver = room.buildGameOver();
        assertThat(gameOver.getGameOver().getReason()).isEqualTo("first_to_finish");
        assertThat(gameOver.getGameOver().getWinnerId()).isEqualTo("A");
    }

    private static TeamAssignment team(String teamId, String... studentIds) {
        return TeamAssignment.newBuilder()
                .setTeamId(teamId)
                .setTeamName("Team " + teamId)
                .addAllStudentIds(List.of(studentIds))
                .build();
    }

    private static RoomState teamRoom(List<TeamAssignment> teamRosters, int progressTarget, WinCondition winCondition) {
        return new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(), MissedStepPolicy.ZERO,
                GameMode.GAME_MODE_TEAM, progressTarget, List.<ProgressStage>of(), SharedResourceType.NONE, 0,
                teamRosters, ScoreAggregation.SUM_ALL, winCondition);
    }
}
