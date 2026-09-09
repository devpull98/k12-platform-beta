package com.uni.realtime.gameengine.domain.game;

import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.scoring.WinConditionEvaluator;

public class CooperativeModeRules implements GameModeRules {

    @Override
    public void applyAnswerOutcome(GameRuleContext context, String studentId, boolean correct) {
        if (correct) {
            int newProgress = context.incrementRoomProgress();
            if (WinConditionEvaluator.progressCompleted(newProgress, context.progressTarget())) {
                context.setGameOver("progress_completed", "");
            }
        } else if (context.sharedResourceType() == SharedResourceType.TIME) {
            context.reduceDeadlineMs(context.sharedResourcePenalty() * 1000L);
        }
    }

    @Override
    public void evaluateTimeUp(GameRuleContext context) {
        context.setGameOver("teacher_ended", "");
    }
}
