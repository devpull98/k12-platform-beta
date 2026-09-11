package com.uni.realtime.gameengine.domain.game;

public interface GameModeRules {
    void applyAnswerOutcome(GameRuleContext context, String studentId, boolean correct);
    void evaluateTimeUp(GameRuleContext context);

    /**
     * Called once a question has closed (the next question is about to start, or the game is
     * ending) so a mode can apply outcomes that can only be judged after every response to that
     * question is in -- e.g. {@code TeamModeRules}'s participation threshold (PO V2.2 §5.2), which
     * cannot be decided from a single {@link #applyAnswerOutcome} call. No-op by default: only a
     * mode that defers judgement to question-close needs to override this.
     */
    default void finalizeQuestionOutcome(GameRuleContext context) {
    }
}
