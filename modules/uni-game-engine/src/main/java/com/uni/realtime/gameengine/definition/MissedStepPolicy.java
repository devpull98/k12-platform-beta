package com.uni.realtime.gameengine.definition;

/**
 * system-architecture.md §4.8: how a late joiner is scored for steps that already passed.
 * The late-join flow itself isn't built in Phase 1 -- this enum exists purely so the schema
 * has the field now rather than needing a breaking change later (plan.md Task 11 AC).
 */
public enum MissedStepPolicy {
    /** Default: 0 points for steps missed before joining -- fair for competitive play. */
    ZERO,
    /** Excluded from the ranking denominator instead of counted as a zero. */
    SKIP,
    /** Allowed to answer late for credit -- self-study only, never competitive play. */
    ALLOW_LATE
}
