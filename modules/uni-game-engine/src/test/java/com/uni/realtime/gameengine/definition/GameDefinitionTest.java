package com.uni.realtime.gameengine.definition;

import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.TeamAssignment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Task 29 (PO V2.2 §3.2 note: "progress_target đã bị loại bỏ khỏi Group B, Engine tự gán
 * progress_target = questions.size()") -- verifies the Group A/B constructor enforces this
 * invariant structurally, not by trusting a caller-supplied progress_target.
 */
class GameDefinitionTest {

    @Test
    void should_computeProgressTarget_asQuestionCount_forGroupAbConstructor() {
        List<Question> questions = List.of(
                new Question("2+2=?", List.of("3", "4"), 1),
                new Question("3+3=?", List.of("5", "6"), 1),
                new Question("4+4=?", List.of("7", "8"), 1));

        GameDefinition definition = new GameDefinition(GameMode.GAME_MODE_COOPERATIVE, questions, 10,
                List.of(), SharedResourceType.NONE, 0, List.<TeamAssignment>of(), ScoreAggregation.SUM_ALL,
                WinCondition.PROGRESS_COMPLETED, 25, LateJoinPolicy.ALLOW_WITH_ZERO_SCORE,
                TeamAssignmentMode.MANUAL, "Rong So Hoc dang de doa...", ProgressDisplayMode.STAGED_VISUAL);

        assertThat(definition.progressTarget()).isEqualTo(3);
        assertThat(definition.questions()).isEqualTo(questions);
        assertThat(definition.maxPlayers()).isEqualTo(10);
        assertThat(definition.roundTimeLimitSeconds()).isEqualTo(25);
        assertThat(definition.introNarrative()).isEqualTo("Rong So Hoc dang de doa...");
        assertThat(definition.progressDisplayMode()).isEqualTo(ProgressDisplayMode.STAGED_VISUAL);
    }

    @Test
    void should_defaultPoV22Fields_forEveryPreExistingConstructor() {
        // Every constructor that predates Task 29 must keep working unchanged for existing
        // callers -- these are the sentinel/default values they now implicitly carry.
        GameDefinition definition = GameDefinition.defaultSoloDefinition();

        assertThat(definition.maxPlayers()).isZero();
        assertThat(definition.questions()).isEmpty();
        assertThat(definition.roundTimeLimitSeconds()).isZero();
        assertThat(definition.lateJoinPolicy()).isEqualTo(LateJoinPolicy.ALLOW_WITH_ZERO_SCORE);
        assertThat(definition.teamAssignmentMode()).isEqualTo(TeamAssignmentMode.MANUAL);
        assertThat(definition.introNarrative()).isEmpty();
        assertThat(definition.progressDisplayMode()).isEqualTo(ProgressDisplayMode.SIMPLE_BAR);
    }
}
