package com.uni.realtime.gameengine.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uni.realtime.protocol.GameMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CMS-facing wire contract (docs/specs/tech-design/cms-game-session-provisioning.md): a
 * request round-trips through JSON to a real, guardrail-validated {@link GameDefinition}, and a
 * request that would fail {@link DefinitionLoader}'s guardrails is rejected here, not later as a
 * RoomActor crash.
 */
class GameSessionDefinitionMapperTest {

    private final GameSessionDefinitionMapper mapper =
            new GameSessionDefinitionMapper(new ObjectMapper(), new DefinitionLoader());

    @Test
    void should_roundTrip_aMinimalCooperativeDefinition_throughJson() throws DefinitionRejectedException {
        GameSessionDefinitionRequest request = new GameSessionDefinitionRequest(
                "COOPERATIVE",
                List.of(new QuestionRequest("2+2=?", List.of("3", "4"), 1)),
                12, List.of(), null, 0, List.of(), null, null, 25, null, null, "intro", null);

        byte[] jsonBytes = mapper.toJsonBytes(request);
        GameDefinition definition = mapper.fromJsonBytes(jsonBytes);

        assertThat(definition.gameMode()).isEqualTo(GameMode.GAME_MODE_COOPERATIVE);
        assertThat(definition.questions()).hasSize(1);
        assertThat(definition.progressTarget()).as("Task 29: Engine always computes this, never trusts a caller value").isEqualTo(1);
        assertThat(definition.roundTimeLimitSeconds()).isEqualTo(25);
        assertThat(definition.introNarrative()).isEqualTo("intro");
    }

    @Test
    void should_defaultOptionalFields_whenOmitted() throws DefinitionRejectedException {
        GameSessionDefinitionRequest request = new GameSessionDefinitionRequest(
                "COOPERATIVE", List.of(new QuestionRequest("q", List.of("a", "b"), 0)),
                0, null, null, 0, null, null, null, 0, null, null, null, null);

        GameDefinition definition = mapper.validate(request);

        assertThat(definition.scoreAggregation()).isEqualTo(ScoreAggregation.SUM_ALL);
        assertThat(definition.winCondition()).isEqualTo(WinCondition.PROGRESS_COMPLETED);
        assertThat(definition.lateJoinPolicy()).isEqualTo(LateJoinPolicy.ALLOW_WITH_ZERO_SCORE);
        assertThat(definition.teamAssignmentMode()).isEqualTo(TeamAssignmentMode.MANUAL);
        assertThat(definition.progressDisplayMode()).isEqualTo(ProgressDisplayMode.SIMPLE_BAR);
        assertThat(definition.introNarrative()).isEmpty();
    }

    @Test
    void should_rejectMissingGameMode() {
        GameSessionDefinitionRequest request = new GameSessionDefinitionRequest(
                null, List.of(new QuestionRequest("q", List.of("a", "b"), 0)),
                0, null, null, 0, null, null, null, 0, null, null, null, null);

        assertThatThrownBy(() -> mapper.validate(request))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("game_mode");
    }

    @Test
    void should_rejectUnknownGameMode() {
        GameSessionDefinitionRequest request = new GameSessionDefinitionRequest(
                "NOT_A_REAL_MODE", List.of(new QuestionRequest("q", List.of("a", "b"), 0)),
                0, null, null, 0, null, null, null, 0, null, null, null, null);

        assertThatThrownBy(() -> mapper.validate(request))
                .isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectWhenDefinitionLoaderGuardrailsFail() {
        // team_count must be within [2, 4] (DefinitionLoader.checkTeamMode) -- exactly one team
        // here must be rejected at this boundary, not surface later as a RoomActor crash.
        GameSessionDefinitionRequest request = new GameSessionDefinitionRequest(
                "TEAM", List.of(new QuestionRequest("q", List.of("a", "b"), 0)),
                12, null, null, 0,
                List.of(new TeamRequest("A", "Team A", List.of("student-1"))),
                null, "FIRST_TO_FINISH", 30, null, null, null, null);

        assertThatThrownBy(() -> mapper.validate(request))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("team_count");
    }

    @Test
    void should_rejectMalformedJson() {
        assertThatThrownBy(() -> mapper.fromJsonBytes("{ not valid json".getBytes()))
                .isInstanceOf(DefinitionRejectedException.class);
    }
}
