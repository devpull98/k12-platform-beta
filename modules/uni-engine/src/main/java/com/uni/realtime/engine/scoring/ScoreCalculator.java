package com.uni.realtime.engine.scoring;

import java.util.List;

/**
 * Isolated on purpose: RoomState never has to know how a formula decides correctness or
 * points, only that it can ask for a score given what the student submitted, what would have
 * been correct, and how fast they answered.
 */
public interface ScoreCalculator {

    /**
     * @param answerIds      the student's chosen answer(s) for the current question
     * @param correctAnswerIds the question's correct answer(s), from the Game Definition/
     *                       question data the room is currently running
     * @param responseTimeMs {@code server_received_at - server_question_started_at} (9.4) —
     *                        never derived from client_timestamp_ms
     * @return points to add to the student's total for this submission
     */
    int award(List<String> answerIds, List<String> correctAnswerIds, long responseTimeMs);
}
