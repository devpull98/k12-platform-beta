package com.uni.realtime.gameengine.domain.game;

public class SoloModeRules implements GameModeRules {

    @Override
    public void applyAnswerOutcome(GameRuleContext context, String studentId, boolean correct) {
        // Solo mode: score is accumulated directly per player, no team or room progress adjustments
    }

    @Override
    public void evaluateTimeUp(GameRuleContext context) {
        context.setGameOver("teacher_ended", "");
    }
}
