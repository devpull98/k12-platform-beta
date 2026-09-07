package com.uni.realtime.gameengine.definition;

/**
 * plan.md Task 11 AC: "không script engine, không biểu thức tuỳ ý -- công thức điểm dùng tập
 * toán tử giới hạn." Game Definitions are data uploaded by an operator (§2.5), so the actual
 * quiz scoring formula -- still an open Product decision, tech-design.md §9.2 Q1 -- must be
 * expressible as data, never as code to execute.
 *
 * <p>This closed set of record variants is the entire guarantee: there is no {@code eval},
 * no reflection, no scripting hook anywhere in {@link #evaluate}, so nothing outside these
 * seven shapes can ever be constructed or run, regardless of what an uploaded definition
 * contains. Deserializing an actual uploaded definition into this tree is future work --
 * building that layer now, with no decided wire format, would be inventing one.
 */
public sealed interface ScoringFormula {

    /**
     * @param isCorrect      1.0 if the answer was correct, 0.0 otherwise
     * @param responseTimeMs {@code server_received_at - server_question_started_at} (§9.4) --
     *                       never derived from client_timestamp_ms
     */
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
