package com.uni.realtime.engine.scoring;

import java.util.List;

/**
 * TEMPORARY. Product has not chosen the Phase-1 quiz scoring formula
 * (tech-design.md §9.2 question 1 / system-architecture.md §7.5 question 2) — this class
 * exists only so RoomActor's FSM/timestamp/dedupe path is testable without waiting on that
 * decision. It is correctness- and speed-blind on purpose: any answer counts as any other,
 * so nothing here can be mistaken for the real formula once it lands.
 *
 * Must be replaced when Product decides, and must never be promoted to production by
 * default — wire the real {@link ScoreCalculator} explicitly when it exists.
 */
public final class PlaceholderScoreCalculator implements ScoreCalculator {

    private static final int FLAT_AWARD = 100;

    @Override
    public int award(List<String> answerIds, long responseTimeMs) {
        return answerIds.isEmpty() ? 0 : FLAT_AWARD;
    }
}
