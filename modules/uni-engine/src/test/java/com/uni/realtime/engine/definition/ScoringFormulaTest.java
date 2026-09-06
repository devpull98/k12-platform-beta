package com.uni.realtime.engine.definition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not required by plan.md's stated Verification (DefinitionLoaderTest only), but ScoringFormula
 * is the concrete realization of the AC's "tập toán tử giới hạn" -- worth proving each operator
 * evaluates correctly and that composing them doesn't need anything resembling script
 * execution.
 */
class ScoringFormulaTest {

    @Test
    void should_returnConstant_regardlessOfInputs() {
        ScoringFormula formula = new ScoringFormula.Constant(42);

        assertThat(formula.evaluate(1, 999)).isEqualTo(42);
        assertThat(formula.evaluate(0, 0)).isEqualTo(42);
    }

    @Test
    void should_returnIsCorrectInput_unchanged() {
        ScoringFormula formula = new ScoringFormula.IsCorrect();

        assertThat(formula.evaluate(1, 12_345)).isEqualTo(1);
        assertThat(formula.evaluate(0, 12_345)).isEqualTo(0);
    }

    @Test
    void should_returnResponseTimeInput_unchanged() {
        ScoringFormula formula = new ScoringFormula.ResponseTimeMs();

        assertThat(formula.evaluate(1, 250)).isEqualTo(250);
    }

    @Test
    void should_combineArithmeticOperators_correctly() {
        ScoringFormula formula = new ScoringFormula.Add(
                new ScoringFormula.Multiply(new ScoringFormula.IsCorrect(), new ScoringFormula.Constant(100)),
                new ScoringFormula.Constant(0));

        assertThat(formula.evaluate(1, 250)).isEqualTo(100);
        assertThat(formula.evaluate(0, 250)).isEqualTo(0);
    }

    @Test
    void should_evaluateSubtractDivideMinMax_correctly() {
        assertThat(new ScoringFormula.Subtract(new ScoringFormula.Constant(10), new ScoringFormula.Constant(3))
                .evaluate(0, 0)).isEqualTo(7);
        assertThat(new ScoringFormula.Divide(new ScoringFormula.Constant(10), new ScoringFormula.Constant(4))
                .evaluate(0, 0)).isEqualTo(2.5);
        assertThat(new ScoringFormula.Min(new ScoringFormula.Constant(10), new ScoringFormula.Constant(4))
                .evaluate(0, 0)).isEqualTo(4);
        assertThat(new ScoringFormula.Max(new ScoringFormula.Constant(10), new ScoringFormula.Constant(4))
                .evaluate(0, 0)).isEqualTo(10);
    }

    @Test
    void should_modelASpeedBonusFormula_usingOnlyTheClosedOperatorSet() {
        // "100 points for correct, minus 1 point per 100ms of response time, floored at 0" --
        // an illustrative example of composing the closed operator set, not a Product decision.
        ScoringFormula speedBonus = new ScoringFormula.Max(
                new ScoringFormula.Constant(0),
                new ScoringFormula.Subtract(
                        new ScoringFormula.Multiply(new ScoringFormula.IsCorrect(), new ScoringFormula.Constant(100)),
                        new ScoringFormula.Divide(new ScoringFormula.ResponseTimeMs(), new ScoringFormula.Constant(100))));

        assertThat(speedBonus.evaluate(1, 2_000)).isEqualTo(80); // 100 - 20
        assertThat(speedBonus.evaluate(1, 15_000)).isEqualTo(0); // would be negative, floored at 0
        assertThat(speedBonus.evaluate(0, 500)).isEqualTo(0); // incorrect -> no base points to subtract from
    }
}
