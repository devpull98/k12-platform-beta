package com.uni.realtime.engine.definition;

import java.util.List;

/**
 * One node in a {@link GameDefinition}'s step graph. {@code nextStepIds} is what makes the
 * graph a graph rather than a fixed list -- a linear quiz just has exactly one next id per
 * step, but the shape supports whatever a future game type needs without a schema change.
 * {@link DefinitionLoader} is what refuses a cycle among these; a {@code Step} on its own
 * makes no claim about being part of a valid DAG.
 */
public record Step(String id, long durationMs, List<String> nextStepIds) {}
