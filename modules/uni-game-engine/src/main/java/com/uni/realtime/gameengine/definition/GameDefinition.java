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
        WinCondition winCondition,
        // PO V2.2 §3 (Group A/B, plan.md P2 Task 29) -- additive, every constructor above this
        // comment predates these and defaults them below rather than being rewritten.
        int maxPlayers,
        List<Question> questions,
        int roundTimeLimitSeconds,
        LateJoinPolicy lateJoinPolicy,
        TeamAssignmentMode teamAssignmentMode,
        String introNarrative,
        ProgressDisplayMode progressDisplayMode) {

    public static GameDefinition defaultSoloDefinition() {
        return new GameDefinition(List.of(), "", TickMode.COALESCE, new ScoringFormula.IsCorrect(), MissedStepPolicy.ZERO, 100);
    }

    public GameDefinition(GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
                          SharedResourceType sharedResourceType, int sharedResourcePenalty,
                          List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition) {
        this(List.of(), "", TickMode.COALESCE, new ScoringFormula.IsCorrect(), MissedStepPolicy.ZERO, 100,
                gameMode, progressTarget, progressStages, sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation, winCondition,
                0, List.of(), 0, LateJoinPolicy.ALLOW_WITH_ZERO_SCORE, TeamAssignmentMode.MANUAL, "", ProgressDisplayMode.SIMPLE_BAR);
    }

    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions,
                GameMode.GAME_MODE_SOLO, 0, List.of(), SharedResourceType.NONE, 0, List.of(), ScoreAggregation.SUM_ALL,
                WinCondition.PROGRESS_COMPLETED, 0, List.of(), 0, LateJoinPolicy.ALLOW_WITH_ZERO_SCORE,
                TeamAssignmentMode.MANUAL, "", ProgressDisplayMode.SIMPLE_BAR);
    }

    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions, gameMode,
                progressTarget, progressStages, sharedResourceType, sharedResourcePenalty, List.of(),
                ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED, 0, List.of(), 0,
                LateJoinPolicy.ALLOW_WITH_ZERO_SCORE, TeamAssignmentMode.MANUAL, "", ProgressDisplayMode.SIMPLE_BAR);
    }

    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions, gameMode,
                progressTarget, progressStages, sharedResourceType, sharedResourcePenalty, teamRosters,
                scoreAggregation, WinCondition.FIRST_TO_FINISH, 0, List.of(), 0,
                LateJoinPolicy.ALLOW_WITH_ZERO_SCORE, TeamAssignmentMode.MANUAL, "", ProgressDisplayMode.SIMPLE_BAR);
    }

    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions, gameMode,
                progressTarget, progressStages, sharedResourceType, sharedResourcePenalty, teamRosters,
                scoreAggregation, winCondition, 0, List.of(), 0, LateJoinPolicy.ALLOW_WITH_ZERO_SCORE,
                TeamAssignmentMode.MANUAL, "", ProgressDisplayMode.SIMPLE_BAR);
    }

    /**
     * PO V2.2 §3 (Group A/B) entry point for Cooperative/Team rooms authored with real question
     * content (plan.md P2 Task 29) -- {@code progress_target} is NEVER taken from the caller here,
     * it is always {@code questions.size()} (PO V2.2 §3.2 note: "Engine tự tính"), so there is no
     * way to construct an inconsistent pairing of the two through this constructor.
     */
    public GameDefinition(GameMode gameMode, List<Question> questions, int maxPlayers,
            List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition,
            int roundTimeLimitSeconds, LateJoinPolicy lateJoinPolicy, TeamAssignmentMode teamAssignmentMode,
            String introNarrative, ProgressDisplayMode progressDisplayMode) {
        this(List.of(), "", TickMode.COALESCE, new ScoringFormula.IsCorrect(), MissedStepPolicy.ZERO, 100,
                gameMode, questions.size(), progressStages, sharedResourceType, sharedResourcePenalty, teamRosters,
                scoreAggregation, winCondition, maxPlayers, questions, roundTimeLimitSeconds, lateJoinPolicy,
                teamAssignmentMode, introNarrative, progressDisplayMode);
    }
}
