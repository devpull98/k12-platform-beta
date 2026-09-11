package com.uni.realtime.gameengine.definition;

import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.TeamAssignment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DefinitionLoader {

    /**
     * P2 Task 28/29 real bug found by {@code RoomSupervisorTest.
     * should_useProvisionedGameDefinition_when_oneWasStoredBeforeFirstJoin} (a genuinely
     * questions-only, steps-empty definition, not a test's hand-written dummy {@code Step}):
     * this method REQUIRED non-empty {@code steps} unconditionally, rejecting every real Group
     * A/B ({@code questions}) definition outright. Every P2 test before this one happened to pass
     * validation only because it manually included one throwaway {@code Step} nobody ever reads
     * (see {@code RoomStateTeamModeTest}/{@code RoomStateWinConditionTest} etc.) -- masking this.
     * Now: a definition needs EITHER at least one {@code step} (P1's DAG mechanism) OR at least
     * one {@code question} (Task 29's flat-list mechanism); the step-graph checks below only run
     * when {@code steps} is the one actually being used.
     */
    public GameDefinition load(GameDefinition definition) throws DefinitionRejectedException {
        if (definition.steps().isEmpty() && definition.questions().isEmpty()) {
            throw new DefinitionRejectedException("definition must have at least one step or question");
        }
        if (definition.tickMode() == TickMode.FIXED) {
            throw new DefinitionRejectedException(
                    "tick_mode FIXED has no Phase 1 implementation (plan.md Task 3 decision #4)");
        }
        if (definition.maxTransitions() <= 0) {
            throw new DefinitionRejectedException("max_transitions must be positive");
        }
        if (definition.missedStepPolicy() != MissedStepPolicy.ZERO) {
            throw new DefinitionRejectedException(
                    "missed_step_policy " + definition.missedStepPolicy()
                            + " has no Phase 1 implementation (only ZERO is honored -- plan.md Task 17)");
        }
        checkPoV22Schema(definition);

        checkCooperativeMode(definition);

        if (!definition.steps().isEmpty()) {
            checkStepGraph(definition);
        }

        return definition;
    }

    private void checkStepGraph(GameDefinition definition) throws DefinitionRejectedException {
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
    }

    /**
     * PO V2.2 §3 (Group A/B, plan.md P2 Task 29) fields. {@code late_join_policy}/
     * {@code team_assignment} have no "must be one of N values" check here because they are
     * already plain Java enums (unlike e.g. {@code shared_resource}) -- the type system already
     * makes an invalid value unrepresentable, a redundant runtime check would just be dead code.
     */
    private void checkPoV22Schema(GameDefinition definition) throws DefinitionRejectedException {
        if (definition.maxPlayers() != 0 && (definition.maxPlayers() < 1 || definition.maxPlayers() > 12)) {
            throw new DefinitionRejectedException(
                    "max_players must be within [1, 12] when specified, got " + definition.maxPlayers());
        }
        for (Question question : definition.questions()) {
            if (question.options().size() < 2) {
                throw new DefinitionRejectedException(
                        "question '" + question.questionText() + "' must have at least 2 options");
            }
            if (question.correctOptionIndex() < 0 || question.correctOptionIndex() >= question.options().size()) {
                throw new DefinitionRejectedException(
                        "question '" + question.questionText() + "' correct_option_index "
                                + question.correctOptionIndex() + " is out of range for " + question.options().size() + " options");
            }
        }
    }

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
        if (definition.winCondition() != WinCondition.PROGRESS_COMPLETED) {
            throw new DefinitionRejectedException(
                    "win_condition " + definition.winCondition() + " is not implemented for GAME_MODE_COOPERATIVE "
                            + "(only PROGRESS_COMPLETED -- plan.md P2 Task 23)");
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
        if (definition.winCondition() != WinCondition.FIRST_TO_FINISH
                && definition.winCondition() != WinCondition.MOST_POINTS_WHEN_TIME_UP) {
            throw new DefinitionRejectedException(
                    "win_condition " + definition.winCondition() + " is not implemented for GAME_MODE_TEAM "
                            + "(only FIRST_TO_FINISH and MOST_POINTS_WHEN_TIME_UP -- plan.md P2 Task 23)");
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
