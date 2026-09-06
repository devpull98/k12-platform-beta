package com.uni.realtime.engine.definition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 11 verification (plan.md): every guardrail fails at {@code load()} itself -- never
 * discovered later at runtime.
 */
class DefinitionLoaderTest {

    private final DefinitionLoader loader = new DefinitionLoader();

    @Test
    void should_returnDefinition_when_linearQuizIsValid() throws DefinitionRejectedException {
        GameDefinition definition = linearQuiz();

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_stepGraphHasACycle() {
        // q1 -> q2 -> q1: a cycle, not a valid DAG, must never reach the game engine.
        GameDefinition cyclic = new GameDefinition(
                List.of(
                        new Step("q1", 25_000, List.of("q2")),
                        new Step("q2", 25_000, List.of("q1"))),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(cyclic))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void should_rejectAtLoadTime_when_cycleIsNotOnTheStartPath() {
        // start -> q1 (a dead end), but q2 <-> q3 cycle elsewhere in the same definition.
        GameDefinition cyclic = new GameDefinition(
                List.of(
                        new Step("start", 25_000, List.of("q1")),
                        new Step("q1", 25_000, List.of()),
                        new Step("q2", 25_000, List.of("q3")),
                        new Step("q3", 25_000, List.of("q2"))),
                "start",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(cyclic)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_tickModeIsFixed() {
        GameDefinition fixedTick = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.FIXED,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(fixedTick))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("FIXED");
    }

    @Test
    void should_rejectAtLoadTime_when_startStepIdIsUndefined() {
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "does-not-exist",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_aStepPointsToAnUndefinedStep() {
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of("ghost"))),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_maxTransitionsIsNotPositive() {
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                0);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_stepsIsEmpty() {
        GameDefinition definition = new GameDefinition(
                List.of(), "q1", TickMode.COALESCE, new ScoringFormula.Constant(100), MissedStepPolicy.ZERO, 50);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    private static GameDefinition linearQuiz() {
        return new GameDefinition(
                List.of(
                        new Step("q1", 25_000, List.of("q2")),
                        new Step("q2", 25_000, List.of("q3")),
                        new Step("q3", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Add(
                        new ScoringFormula.Multiply(new ScoringFormula.IsCorrect(), new ScoringFormula.Constant(100)),
                        new ScoringFormula.Constant(0)),
                MissedStepPolicy.ZERO,
                50);
    }
}
