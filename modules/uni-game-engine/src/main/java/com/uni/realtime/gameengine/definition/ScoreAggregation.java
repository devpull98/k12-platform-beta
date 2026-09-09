package com.uni.realtime.gameengine.definition;

/**
 * `score_aggregation` (plan.md P2 Task 22 AC), `GAME_MODE_TEAM` only: how a team's displayed/
 * ranked score is derived from its members' individual scores. The PO's broader
 * `score_aggregation` list (INCLASS-GAME-001-v2.1 §3.1) also has {@code first_correct_only}/
 * {@code majority_vote} -- only the two values Task 22's own acceptance criteria name are
 * implemented here.
 */
public enum ScoreAggregation {
    SUM_ALL,
    AVERAGE
}
