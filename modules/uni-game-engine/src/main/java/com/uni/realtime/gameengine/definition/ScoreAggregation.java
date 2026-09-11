package com.uni.realtime.gameengine.definition;

public enum ScoreAggregation {
    SUM_ALL,
    AVERAGE,
    /**
     * PO V2.2 §3.1 (Group A) new value -- not implemented in {@code RoomState.computeTeamScore()}
     * yet (still only branches on {@code AVERAGE} vs everything-else-sums), see plan.md Task 29.
     */
    FIRST_CORRECT_ONLY
}
