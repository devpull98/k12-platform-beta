package com.uni.realtime.gameengine.definition;

import java.util.List;

/**
 * Enough to play a whole quiz (plan.md Task 11). Constructing one makes no safety claim by
 * itself -- {@link DefinitionLoader} is what enforces the guardrails (§10.5) before a
 * definition is trusted: no cycle among {@code steps}, {@code tickMode} must be
 * {@link TickMode#COALESCE} in Phase 1, {@code maxTransitions} must be positive.
 */
public record GameDefinition(
        List<Step> steps,
        String startStepId,
        TickMode tickMode,
        ScoringFormula scoringFormula,
        MissedStepPolicy missedStepPolicy,
        int maxTransitions) {}
