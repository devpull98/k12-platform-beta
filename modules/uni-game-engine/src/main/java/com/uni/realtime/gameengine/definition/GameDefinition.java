package com.uni.realtime.gameengine.definition;

import com.uni.realtime.protocol.GameMode;

import java.util.List;

/**
 * Enough to play a whole quiz (plan.md Task 11). Constructing one makes no safety claim by
 * itself -- {@link DefinitionLoader} is what enforces the guardrails (§10.5) before a
 * definition is trusted: no cycle among {@code steps}, {@code tickMode} must be
 * {@link TickMode#COALESCE} in Phase 1, {@code maxTransitions} must be positive.
 *
 * @param gameMode {@code GAME_MODE_SOLO}/{@code UNSPECIFIED} for every Phase 1 definition (the
 *     6-arg constructor below defaults to this). {@code GAME_MODE_COOPERATIVE} is the only mode
 *     {@code RoomState} implements so far (P2 Task 21); {@code TEAM}/{@code INDIVIDUAL} are
 *     accepted here (schema) but rejected by {@link DefinitionLoader} until P2 Task 22 exists.
 * @param progressTarget number of correct answers (whole room, `cooperative`) needed for 100%
 *     progress. Ignored for {@code GAME_MODE_SOLO}.
 * @param progressStages `staged_visual` milestones (INCLASS-GAME-001-v2.1 §3.2), ascending by
 *     {@link ProgressStage#milestonePercent}. Empty for {@code simple_bar}/no staged visual.
 * @param sharedResourceType what a wrong answer costs the room, `cooperative` only.
 * @param sharedResourcePenalty magnitude of that cost -- seconds for {@link SharedResourceType#TIME}.
 *     Meaningless (and unused) for {@link SharedResourceType#NONE}.
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
        int sharedResourcePenalty) {

    /**
     * P2 Task 21: every Phase 1 call site (tests, {@code RoomSupervisor}) that has no
     * cooperative-mode config to offer keeps compiling unchanged -- same additive-overload shape
     * {@code RoomActor.create}/{@code RoomState} already use for {@code missedStepPolicy}/
     * {@code snapshotStore}.
     */
    public GameDefinition(List<Step> steps, String startStepId, TickMode tickMode,
            ScoringFormula scoringFormula, MissedStepPolicy missedStepPolicy, int maxTransitions) {
        this(steps, startStepId, tickMode, scoringFormula, missedStepPolicy, maxTransitions,
                GameMode.GAME_MODE_SOLO, 0, List.of(), SharedResourceType.NONE, 0);
    }
}
