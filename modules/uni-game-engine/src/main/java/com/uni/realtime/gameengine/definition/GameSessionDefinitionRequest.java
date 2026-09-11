package com.uni.realtime.gameengine.definition;

import java.util.List;

/**
 * CMS-facing JSON contract for provisioning one room's {@link GameDefinition} ahead of the first
 * student joining -- see docs/specs/tech-design/cms-game-session-provisioning.md for the full
 * field-by-field contract this maps onto, and why this is a wire-stable DTO deliberately kept
 * separate from {@link GameDefinition}'s own Java shape (that shape is free to change; this
 * contract is not, without a version bump CMS also updates for).
 *
 * <p>Every field except {@code gameMode} and {@code questions} is optional -- omit it (or send
 * {@code null}/{@code 0}) to get the same default {@link GameDefinition} would use for anything
 * Task 29 added.
 */
public record GameSessionDefinitionRequest(
        String gameMode,
        List<QuestionRequest> questions,
        // Integer, not int: Jackson (Spring Boot 4's Jackson-3-based @RequestBody converter, in
        // particular) rejects a MISSING JSON field mapped to a primitive with "Cannot map `null`
        // into type `int`" -- confirmed with a real request against a running Engine, not a
        // guess. Every "optional" field below that used to be primitive is boxed for the same
        // reason; GameSessionDefinitionMapper treats a null the same as an omitted field.
        Integer maxPlayers,
        List<ProgressStageRequest> progressStages,
        String sharedResourceType,
        Integer sharedResourcePenalty,
        List<TeamRequest> teams,
        String scoreAggregation,
        String winCondition,
        Integer roundTimeLimitSeconds,
        String lateJoinPolicy,
        String teamAssignmentMode,
        String introNarrative,
        String progressDisplayMode) {
}
