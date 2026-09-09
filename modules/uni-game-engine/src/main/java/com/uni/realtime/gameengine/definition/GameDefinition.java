package com.uni.realtime.gameengine.definition;

import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.TeamAssignment;

import java.util.List;

/**
 * Enough to play a whole quiz (plan.md Task 11). Constructing one makes no safety claim by
 * itself -- {@link DefinitionLoader} is what enforces the guardrails (§10.5) before a
 * definition is trusted: no cycle among {@code steps}, {@code tickMode} must be
 * {@link TickMode#COALESCE} in Phase 1, {@code maxTransitions} must be positive.
 *
 * @param gameMode {@code GAME_MODE_SOLO}/{@code UNSPECIFIED} for every Phase 1 definition (the
 *     6-arg constructor below defaults to this). {@code COOPERATIVE} (P2 Task 21) and
 *     {@code TEAM} (P2 Task 22) are the only modes {@code RoomState} implements so far;
 *     {@code INDIVIDUAL} is accepted here (schema) but rejected by {@link DefinitionLoader}.
 * @param progressTarget number of correct answers needed for 100% progress -- whole-room count
 *     for {@code cooperative}, per-team count for {@code team}. Ignored for {@code SOLO}.
 * @param progressStages `staged_visual` milestones (INCLASS-GAME-001-v2.1 §3.2), ascending by
 *     {@link ProgressStage#milestonePercent}. Empty for {@code simple_bar}/no staged visual.
 * @param sharedResourceType what a wrong answer costs the room, `cooperative` only.
 * @param sharedResourcePenalty magnitude of that cost -- seconds for {@link SharedResourceType#TIME}.
 *     Meaningless (and unused) for {@link SharedResourceType#NONE}.
 * @param teamRosters {@code GAME_MODE_TEAM} only, 2-4 entries: each team's fixed `team_id`/
 *     `team_name`/`student_ids`, decided upstream (CMS/teacher, PO §3.1 `team_assignment`) before
 *     this definition ever reaches the Engine -- {@code RoomState} only ever READS this to look
 *     up which team a joining {@code student_id} belongs to, it never invents an assignment
 *     algorithm of its own (same "static config in, Engine never authors gameplay data"
 *     principle {@code steps}/{@code scoringFormula} already follow). {@code team_score} on each
 *     entry is ignored here -- {@code RoomState} computes and overwrites it on every broadcast.
 * @param scoreAggregation how each team's displayed score is derived from its members' scores,
 *     {@code GAME_MODE_TEAM} only.
 */
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
        ScoreAggregation scoreAggregation) {

    /**
     * P2 Task 21: every Phase 1 call site (tests, {@code RoomSupervisor}) that has no
     * cooperative-mode config to offer keeps compiling unchanged -- same additive-overload shape
     * {@code RoomActor.create}/{@code RoomState} already use for {@code missedStepPolicy}/
     * {@code snapshotStore}.
     */
    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions,
                GameMode.GAME_MODE_SOLO, 0, List.of(), SharedResourceType.NONE, 0, List.of(), ScoreAggregation.SUM_ALL);
    }

    /**
     * P2 Task 21's own 11-arg canonical shape (cooperative-mode config, no team config) -- kept
     * so {@code DefinitionLoaderTest}'s existing cooperative-mode call sites keep compiling
     * unchanged, same additive-overload reasoning as above.
     */
    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions, gameMode,
                progressTarget, progressStages, sharedResourceType, sharedResourcePenalty, List.of(),
                ScoreAggregation.SUM_ALL);
    }
}
