package com.uni.realtime.gameengine.definition;

public sealed interface ScoringFormula {

    double evaluate(double isCorrect, double responseTimeMs);

    record Constant(double value) implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return value;
        }
    }

    record IsCorrect() implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return isCorrect;
        }
    }

    record ResponseTimeMs() implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return responseTimeMs;
        }
    }

    record Add(ScoringFormula left, ScoringFormula right) implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return left.evaluate(isCorrect, responseTimeMs) + right.evaluate(isCorrect, responseTimeMs);
        }
    }

    record Subtract(ScoringFormula left, ScoringFormula right) implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return left.evaluate(isCorrect, responseTimeMs) - right.evaluate(isCorrect, responseTimeMs);
        }
    }

    record Multiply(ScoringFormula left, ScoringFormula right) implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return left.evaluate(isCorrect, responseTimeMs) * right.evaluate(isCorrect, responseTimeMs);
        }
    }

    record Divide(ScoringFormula left, ScoringFormula right) implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return left.evaluate(isCorrect, responseTimeMs) / right.evaluate(isCorrect, responseTimeMs);
        }
    }

    record Min(ScoringFormula left, ScoringFormula right) implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return Math.min(left.evaluate(isCorrect, responseTimeMs), right.evaluate(isCorrect, responseTimeMs));
        }
    }

    record Max(ScoringFormula left, ScoringFormula right) implements ScoringFormula {
        @Override
        public double evaluate(double isCorrect, double responseTimeMs) {
            return Math.max(left.evaluate(isCorrect, responseTimeMs), right.evaluate(isCorrect, responseTimeMs));
        }
    }
}
