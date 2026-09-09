package com.uni.realtime.gameengine.definition;

import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.TeamAssignment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Load-time guardrails (§10.5, plan.md Task 11). A {@link GameDefinition} is untrusted data
 * uploaded by an operator, so every one of these checks runs once, here, before anything else
 * ever sees the definition -- a cycle or an unimplemented {@code tick_mode} must fail the
 * upload, never surface as a hang or a silent fallback discovered mid-session.
 */
public final class DefinitionLoader {

    public GameDefinition load(GameDefinition definition) throws DefinitionRejectedException {
        if (definition.steps().isEmpty()) {
            throw new DefinitionRejectedException("definition must have at least one step");
        }
        if (definition.tickMode() == TickMode.FIXED) {
            throw new DefinitionRejectedException(
                    "tick_mode FIXED has no Phase 1 implementation (plan.md Task 3 decision #4)");
        }
        if (definition.maxTransitions() <= 0) {
            throw new DefinitionRejectedException("max_transitions must be positive");
        }
        if (definition.missedStepPolicy() != MissedStepPolicy.ZERO) {
            // Task 17 / B4 (docs/work/.../_context.md): RoomActor/RoomState have no late-join
            // flow and never consult this field -- SKIP would silently behave as ZERO, and
            // ALLOW_LATE additionally blows the < 5 KB Hot Snapshot budget (system-architecture.md
            // §4.8: "cấm dùng trong thi đấu... làm phình snapshot > 5 KB"). Reject at load time
            // rather than let either lie dormant until someone trusts a policy the engine
            // silently ignores -- same fail-fast pattern as tick_mode FIXED above.
            throw new DefinitionRejectedException(
                    "missed_step_policy " + definition.missedStepPolicy()
                            + " has no Phase 1 implementation (only ZERO is honored -- plan.md Task 17)");
        }

        checkCooperativeMode(definition);

        Map<String, Step> stepsById = new HashMap<>();
        for (Step step : definition.steps()) {
            stepsById.put(step.id(), step);
        }

        if (!stepsById.containsKey(definition.startStepId())) {
            throw new DefinitionRejectedException(
                    "start_step_id '" + definition.startStepId() + "' is not among the defined steps");
        }
        for (Step step : definition.steps()) {
            for (String nextId : step.nextStepIds()) {
                if (!stepsById.containsKey(nextId)) {
                    throw new DefinitionRejectedException(
                            "step '" + step.id() + "' points to undefined step '" + nextId + "'");
                }
            }
        }

        rejectCycles(stepsById);

        return definition;
    }

    /**
     * P2 Task 21 (INCLASS-GAME-001-v2.1 §3): guardrails for the cooperative-progress fields --
     * same fail-fast-before-anything-sees-it posture as every other check in this class. Modes
     * without a {@code RoomState} implementation yet are rejected outright rather than silently
     * behaving like SOLO, same reasoning as {@code missed_step_policy} above.
     */
    private void checkCooperativeMode(GameDefinition definition) throws DefinitionRejectedException {
        if (definition.gameMode() == GameMode.GAME_MODE_INDIVIDUAL) {
            throw new DefinitionRejectedException(
                    "game_mode INDIVIDUAL has no Phase 2 RoomState implementation yet "
                            + "(only COOPERATIVE, TEAM and SOLO -- no plan.md task covers it)");
        }
        if (definition.gameMode() == GameMode.GAME_MODE_TEAM) {
            checkTeamMode(definition);
            return;
        }
        if (definition.gameMode() != GameMode.GAME_MODE_COOPERATIVE) {
            return;
        }
        if (definition.progressTarget() <= 0) {
            throw new DefinitionRejectedException("progress_target must be positive for GAME_MODE_COOPERATIVE");
        }
        checkProgressStages(definition);
        if (definition.sharedResourceType() == SharedResourceType.LIVES) {
            throw new DefinitionRejectedException(
                    "shared_resource_type LIVES has no Phase 2 RoomState implementation yet "
                            + "(no spec'd starting lives count -- plan.md P2 Task 23)");
        }
        if (definition.sharedResourceType() == SharedResourceType.TIME && definition.sharedResourcePenalty() <= 0) {
            throw new DefinitionRejectedException("shared_resource penalty must be positive when type is TIME");
        }
    }

    /**
     * P2 Task 22 (INCLASS-GAME-001-v2.1 §3.1): {@code team_rosters} must have 2-4 entries (PO's
     * {@code team_count}), each with at least one member, and no student_id in more than one
     * roster (a student cannot play for two teams at once).
     */
    private void checkTeamMode(GameDefinition definition) throws DefinitionRejectedException {
        if (definition.teamRosters().size() < 2 || definition.teamRosters().size() > 4) {
            throw new DefinitionRejectedException(
                    "team_count must be within [2, 4] for GAME_MODE_TEAM, got " + definition.teamRosters().size());
        }
        Set<String> seenStudentIds = new HashSet<>();
        for (TeamAssignment team : definition.teamRosters()) {
            if (team.getStudentIdsList().isEmpty()) {
                throw new DefinitionRejectedException("team '" + team.getTeamId() + "' has no members");
            }
            for (String studentId : team.getStudentIdsList()) {
                if (!seenStudentIds.add(studentId)) {
                    throw new DefinitionRejectedException(
                            "student_id '" + studentId + "' appears in more than one team roster");
                }
            }
        }
        if (definition.progressTarget() <= 0) {
            throw new DefinitionRejectedException("progress_target must be positive for GAME_MODE_TEAM");
        }
        checkProgressStages(definition);
    }

    private void checkProgressStages(GameDefinition definition) throws DefinitionRejectedException {
        int previousMilestone = -1;
        for (ProgressStage stage : definition.progressStages()) {
            if (stage.milestonePercent() < 0 || stage.milestonePercent() > 100) {
                throw new DefinitionRejectedException(
                        "progress_stages milestone_pct " + stage.milestonePercent() + " must be within [0, 100]");
            }
            if (stage.milestonePercent() <= previousMilestone) {
                throw new DefinitionRejectedException(
                        "progress_stages must be strictly ascending by milestone_pct");
            }
            previousMilestone = stage.milestonePercent();
        }
    }

    /** Classic white/gray/black DFS: a back-edge to a node still on the current path is a cycle. */
    private void rejectCycles(Map<String, Step> stepsById) throws DefinitionRejectedException {
        Set<String> visited = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        for (String stepId : stepsById.keySet()) {
            if (!visited.contains(stepId)) {
                visit(stepId, stepsById, visited, onPath);
            }
        }
    }

    private void visit(String stepId, Map<String, Step> stepsById, Set<String> visited, Set<String> onPath)
            throws DefinitionRejectedException {
        visited.add(stepId);
        onPath.add(stepId);
        for (String nextId : stepsById.get(stepId).nextStepIds()) {
            if (onPath.contains(nextId)) {
                throw new DefinitionRejectedException(
                        "cycle detected in step graph: '" + stepId + "' -> '" + nextId + "'");
            }
            if (!visited.contains(nextId)) {
                visit(nextId, stepsById, visited, onPath);
            }
        }
        onPath.remove(stepId);
    }
}
