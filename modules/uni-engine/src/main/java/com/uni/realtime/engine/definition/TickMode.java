package com.uni.realtime.engine.definition;

/**
 * §6.2 / ADR-4 / decision #4: {@code COALESCE} is the only mode Phase 1 implements.
 * {@code FIXED} exists in the schema so Phase 2 does not need a breaking change, but
 * {@link DefinitionLoader} rejects it at load time -- never silently falls back to COALESCE.
 */
public enum TickMode {
    COALESCE,
    FIXED
}
