package com.uni.realtime.gameengine.scoring;

import com.uni.realtime.gameengine.definition.ScoringFormula;

import java.util.List;
import java.util.Set;

public final class FormulaScoreCalculator implements ScoreCalculator {

    private final ScoringFormula formula;

    public FormulaScoreCalculator(ScoringFormula formula) {
        this.formula = formula;
    }

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
