package com.uni.realtime.gameengine.domain.game;

import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.scoring.WinConditionEvaluator;

public class IndividualModeRules implements GameModeRules {

    @Override
    public void applyAnswerOutcome(GameRuleContext context, String studentId, boolean correct) {
        if (!correct) {
            return;
        }
        int studentScore = context.totalScoreOf(studentId);
        if (context.winCondition() == WinCondition.PROGRESS_COMPLETED
                && WinConditionEvaluator.progressCompleted(studentScore, context.progressTarget())) {
            context.setGameOver("progress_completed", studentId);
        }
    }

    @Override
    public void evaluateTimeUp(GameRuleContext context) {
        context.setGameOver("teacher_ended", "");
    }
}
