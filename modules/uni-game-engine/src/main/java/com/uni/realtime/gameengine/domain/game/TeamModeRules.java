package com.uni.realtime.gameengine.domain.game;

import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.scoring.WinConditionEvaluator;
import com.uni.realtime.protocol.TeamAssignment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TeamModeRules implements GameModeRules {

    @Override
    public void applyAnswerOutcome(GameRuleContext context, String studentId, boolean correct) {
        // Team progress is judged once the question closes, not per answer (PO V2.2 §5.2's
        // participation threshold cannot be decided from a single answer) -- see
        // finalizeQuestionOutcome.
    }

    @Override
    public void finalizeQuestionOutcome(GameRuleContext context) {
        for (TeamAssignment roster : context.teamRosters()) {
            List<String> memberIds = roster.getStudentIdsList();
            if (memberIds.isEmpty()) {
                continue;
            }
            long respondedCount = memberIds.stream().filter(context::hasAnsweredCurrentQuestion).count();
            boolean anyCorrect = memberIds.stream().anyMatch(context::hasAnsweredCurrentQuestionCorrectly);
            double participatedRatio = (double) respondedCount / memberIds.size();

            if (anyCorrect && participatedRatio >= 0.5) {
                String teamId = roster.getTeamId();
                int newProgress = context.incrementTeamProgress(teamId);
                if (context.winCondition() == WinCondition.FIRST_TO_FINISH
                        && WinConditionEvaluator.firstToFinish(newProgress, context.progressTarget())) {
                    context.setGameOver("first_to_finish", teamId);
                }
            }
        }
    }

    @Override
    public void evaluateTimeUp(GameRuleContext context) {
        if (context.winCondition() == WinCondition.MOST_POINTS_WHEN_TIME_UP) {
            Map<String, Integer> scoreByTeam = new HashMap<>();
            Map<String, Long> responseTimeByTeam = new HashMap<>();
            for (TeamAssignment roster : context.teamRosters()) {
                scoreByTeam.put(roster.getTeamId(), context.computeTeamScore(roster));
                responseTimeByTeam.put(roster.getTeamId(), context.teamResponseTimeMs(roster.getTeamId()));
            }
            String winnerId = WinConditionEvaluator
                    .singleHighestScorerByResponseTime(scoreByTeam, responseTimeByTeam)
                    .orElse("");
            context.setGameOver("most_points_when_time_up", winnerId);
        } else {
            context.setGameOver("teacher_ended", "");
        }
    }
}
