package com.uni.realtime.gameengine.domain.game;

import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.scoring.WinConditionEvaluator;
import com.uni.realtime.protocol.TeamAssignment;

import java.util.HashMap;
import java.util.Map;

public class TeamModeRules implements GameModeRules {

    @Override
    public void applyAnswerOutcome(GameRuleContext context, String studentId, boolean correct) {
        if (!correct) {
            return;
        }
        String teamId = context.teamIdOf(studentId);
        if (teamId.isEmpty()) {
            return;
        }
        int newProgress = context.incrementTeamProgress(teamId);
        if (context.winCondition() == WinCondition.FIRST_TO_FINISH
                && WinConditionEvaluator.firstToFinish(newProgress, context.progressTarget())) {
            context.setGameOver("first_to_finish", teamId);
        }
    }

    @Override
    public void evaluateTimeUp(GameRuleContext context) {
        if (context.winCondition() == WinCondition.MOST_POINTS_WHEN_TIME_UP) {
            Map<String, Integer> scoreByTeam = new HashMap<>();
            for (TeamAssignment roster : context.teamRosters()) {
                scoreByTeam.put(roster.getTeamId(), context.computeTeamScore(roster));
            }
            String winnerId = WinConditionEvaluator.singleHighestScorer(scoreByTeam).orElse("");
            context.setGameOver("most_points_when_time_up", winnerId);
        } else {
            context.setGameOver("teacher_ended", "");
        }
    }
}
