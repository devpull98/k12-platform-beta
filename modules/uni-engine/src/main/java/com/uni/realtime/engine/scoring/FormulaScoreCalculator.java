package com.uni.realtime.engine.scoring;

import com.uni.realtime.engine.definition.ScoringFormula;

import java.util.List;
import java.util.Set;

/**
 * Bridges {@link ScoreCalculator} to {@link ScoringFormula} (Task 11's closed, script-free
 * expression tree): this class computes correctness -- an exact match, never a superset or
 * subset, and never on an empty submission -- and then hands {@code isCorrect}/
 * {@code responseTimeMs} to whatever formula the room's Game Definition specifies. Nothing
 * about the actual point values lives in Java code; it lives entirely in the
 * {@link ScoringFormula} instance passed in.
 */
public final class FormulaScoreCalculator implements ScoreCalculator {

    private final ScoringFormula formula;

    public FormulaScoreCalculator(ScoringFormula formula) {
        this.formula = formula;
    }

    /**
     * The Phase-1 quiz formula, decided by Product 2026-09-06 (system-architecture.md §2.5):
     * single-choice-from-four, binary correct/incorrect, no speed bonus.
     */
    public static FormulaScoreCalculator binaryChoice() {
        return new FormulaScoreCalculator(
                new ScoringFormula.Multiply(new ScoringFormula.IsCorrect(), new ScoringFormula.Constant(100)));
    }

    @Override
    public int award(List<String> answerIds, List<String> correctAnswerIds, long responseTimeMs) {
        boolean isCorrect = !answerIds.isEmpty() && Set.copyOf(answerIds).equals(Set.copyOf(correctAnswerIds));
        return (int) Math.round(formula.evaluate(isCorrect ? 1.0 : 0.0, responseTimeMs));
    }
}
