package com.uni.realtime.gameengine.definition;

/**
 * PO V2.2 §3.1 (Group A) {@code late_join_policy}. Not implemented on the {@code RoomState.joinRoom()}
 * path yet (still only applies {@link MissedStepPolicy} for catch-up scoring) -- see plan.md Task
 * 29's AC before wiring {@code BLOCK_AFTER_START} to actually reject a join.
 */
public enum LateJoinPolicy {
    ALLOW_WITH_ZERO_SCORE,
    BLOCK_AFTER_START
}
