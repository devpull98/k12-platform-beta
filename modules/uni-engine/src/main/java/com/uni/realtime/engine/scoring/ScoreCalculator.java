package com.uni.realtime.engine.scoring;

import java.util.List;

/**
 * Isolated on purpose: the Phase-1 quiz scoring formula is a Product decision that has not
 * been made yet (tech-design.md §9.2 question 1). Keeping this boundary separate from
 * {@link com.uni.realtime.engine.room.RoomState} lets the FSM / timestamp / dedupe path be
 * built and tested now, and the real formula dropped in later without touching RoomState.
 */
public interface ScoreCalculator {

    /**
     * @param answerIds     the student's chosen answer(s) for the current question
     * @param responseTimeMs {@code server_received_at - server_question_started_at} (9.4) —
     *                        never derived from client_timestamp_ms
     * @return points to add to the student's total for this submission
     */
    int award(List<String> answerIds, long responseTimeMs);
}
