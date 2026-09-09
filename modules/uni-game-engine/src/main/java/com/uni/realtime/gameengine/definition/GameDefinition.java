package com.uni.realtime.gameengine.definition;

import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.TeamAssignment;

import java.util.List;

public record GameDefinition(
        List<Step> steps,
        String startStepId,
        TickMode tickMode,
        ScoringFormula scoringFormula,
        MissedStepPolicy missedStepPolicy,
        int maxTransitions,
        GameMode gameMode,
        int progressTarget,
        List<ProgressStage> progressStages,
        SharedResourceType sharedResourceType,
        int sharedResourcePenalty,
        List<TeamAssignment> teamRosters,
        ScoreAggregation scoreAggregation,
        WinCondition winCondition) {

    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions,
                GameMode.GAME_MODE_SOLO, 0, List.of(), SharedResourceType.NONE, 0, List.of(), ScoreAggregation.SUM_ALL,
                WinCondition.PROGRESS_COMPLETED);
    }

    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions, gameMode,
                progressTarget, progressStages, sharedResourceType, sharedResourcePenalty, List.of(),
                ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED);
    }

    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions, gameMode,
                progressTarget, progressStages, sharedResourceType, sharedResourcePenalty, teamRosters,
                scoreAggregation, WinCondition.FIRST_TO_FINISH);
    }
}
