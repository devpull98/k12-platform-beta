package com.uni.realtime.engine.definition;

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
