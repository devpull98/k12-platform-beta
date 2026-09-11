package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.GameDefinition;
import com.uni.realtime.gameengine.definition.LateJoinPolicy;
import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressDisplayMode;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.Question;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.TeamAssignmentMode;
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
 * P2 Task 28 (PO V2.2 SS4): "Engine tự động chuyển IN_PROGRESS & phát câu hỏi 1 (không chờ GV
 * bấm thêm)" -- a room authored with {@code questions} (Task 29 Group A) fires question 1 the
 * instant {@link RoomState#startGame()} runs, with no separate StartQuestion needed. RULES_DISPLAY
 * itself is not asserted as an observable pause here -- the 2026-09-11 user decision was that the
 * server does not hold it for any duration (no PO-specified number, no timer built), so phase()
 * is PLAYING by the time startGame() returns; see plan.md Task 28 for that decision.
 */
class RoomStateAutoStartFirstQuestionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void should_firePhaseAndFirstQuestion_whenStartingAGameAuthoredWithQuestions() {
        List<Question> questions = List.of(
                new Question("2+2=?", List.of("3", "4"), 1),
                new Question("3+3=?", List.of("5", "6"), 1));
        RoomState room = cooperativeRoomWithQuestions(questions, 30);
        room.joinRoom("student-01", "S1");

        room.startGame();

        assertThat(room.phase()).isEqualTo(GamePhase.PLAYING);
        assertThat(room.currentQuestionId()).isEqualTo("q-1");
        assertThat(room.deadlineMs()).isEqualTo(CLOCK.millis() + 30_000L);
    }

    @Test
    void should_acceptTheConfiguredCorrectAnswer_forTheAutoStartedFirstQuestion() {
        List<Question> questions = List.of(new Question("2+2=?", List.of("3", "4"), 1));
        RoomState room = cooperativeRoomWithQuestions(questions, 30);
        room.joinRoom("student-01", "S1");
        room.startGame();

        GameMessage ack = room.submitAnswer("student-01", 1L, "q-1", List.of("1"));

        assertThat(ack.getAnswerAck().getAccepted()).isTrue();
        assertThat(ack.getAnswerAck().getAwardedPoints()).isGreaterThan(0);
    }

    @Test
    void should_useDefaultQuestionDuration_when_roundTimeLimitIsUnset() {
        List<Question> questions = List.of(new Question("2+2=?", List.of("3", "4"), 1));
        RoomState room = cooperativeRoomWithQuestions(questions, 0); // round_time_limit unset

        room.startGame();

        assertThat(room.deadlineMs()).isEqualTo(CLOCK.millis() + 25_000L);
    }

    @Test
    void should_keepOldPlayingOnlyBehavior_whenNoQuestionsAreConfigured() {
        // Every room built before Task 29 (and still the only one RoomSupervisor.spawnRoom()'s
        // real join path can reach -- plan.md Task 11/28) must see zero behavior change.
        RoomState room = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());

        room.startGame();

        assertThat(room.phase()).isEqualTo(GamePhase.PLAYING);
        assertThat(room.currentQuestionId()).isNull();
    }

    private static RoomState cooperativeRoomWithQuestions(List<Question> questions, int roundTimeLimitSeconds) {
        GameDefinition definition = new GameDefinition(GameMode.GAME_MODE_COOPERATIVE, questions, 12,
                List.<ProgressStage>of(), SharedResourceType.NONE, 0, List.<TeamAssignment>of(),
                ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED, roundTimeLimitSeconds,
                LateJoinPolicy.ALLOW_WITH_ZERO_SCORE, TeamAssignmentMode.MANUAL, "intro",
                ProgressDisplayMode.SIMPLE_BAR);
        return new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(), MissedStepPolicy.ZERO, definition);
    }
}
