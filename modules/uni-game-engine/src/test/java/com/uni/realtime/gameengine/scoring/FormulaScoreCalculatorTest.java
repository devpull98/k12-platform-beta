package com.uni.realtime.gameengine.scoring;

import com.uni.realtime.gameengine.definition.ScoringFormula;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FormulaScoreCalculatorTest {

    @Test
    void should_determineCorrectnessByExactMatch_regardlessOfFormula() {
        // A trivial formula that just echoes isCorrect as 0/1, isolating the correctness
        // check (answerIds vs correctAnswerIds) from whatever the formula does with it.
        FormulaScoreCalculator calculator = new FormulaScoreCalculator(new ScoringFormula.IsCorrect());

        assertThat(calculator.award(List.of("b"), List.of("b"), 250)).isEqualTo(1);
        assertThat(calculator.award(List.of("a"), List.of("b"), 250)).isZero();
        assertThat(calculator.award(List.of(), List.of("b"), 250)).as("empty submission is never correct").isZero();
        assertThat(calculator.award(List.of("a", "b"), List.of("b"), 250))
                .as("a superset of the correct answer must not count as correct").isZero();
    }

    @Test
    void should_evaluateWhicheverFormulaIsConfigured() {
        // Proves the calculator is genuinely generic: a completely different formula (flat 50
        // regardless of correctness) drives the result, not a hardcoded 100/0 in Java.
        FormulaScoreCalculator calculator = new FormulaScoreCalculator(new ScoringFormula.Constant(50));

        assertThat(calculator.award(List.of("a"), List.of("a"), 250)).isEqualTo(50);
        assertThat(calculator.award(List.of("wrong"), List.of("a"), 250)).isEqualTo(50);
    }

    @Test
    void should_matchThePhase1DecidedFormula_when_usingBinaryChoiceFactory() {
        // system-architecture.md §2.5 (Product, 2026-09-06): correct = 100, incorrect = 0,
        // no speed bonus.
        FormulaScoreCalculator calculator = FormulaScoreCalculator.binaryChoice();

        assertThat(calculator.award(List.of("c"), List.of("c"), 10)).isEqualTo(100);
        assertThat(calculator.award(List.of("d"), List.of("c"), 10)).isZero();
        assertThat(calculator.award(List.of("c"), List.of("c"), 24_999))
                .as("no speed bonus: fast and slow correct answers score identically")
                .isEqualTo(calculator.award(List.of("c"), List.of("c"), 10));
    }
}
