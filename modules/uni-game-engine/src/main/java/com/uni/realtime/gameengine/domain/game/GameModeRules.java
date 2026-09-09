package com.uni.realtime.gameengine.domain.game;

public interface GameModeRules {
    void applyAnswerOutcome(GameRuleContext context, String studentId, boolean correct);
    void evaluateTimeUp(GameRuleContext context);
}
