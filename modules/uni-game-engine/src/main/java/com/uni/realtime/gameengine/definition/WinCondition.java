package com.uni.realtime.gameengine.definition;

/**
 * `win_condition` (INCLASS-GAME-001-v2.1 §3.2, Group B). {@code RoomState}/{@code
 * WinConditionEvaluator} (P2 Task 23) decide the room's FSM transition to {@code FINISHED} from
 * this, instead of every game mode hardcoding its own notion of "the game is over".
 */
public enum WinCondition {
    /** {@code cooperative}: whole-room progress reached {@code progress_target}. */
    PROGRESS_COMPLETED,
    /** {@code team}: the first team whose progress reaches {@code progress_target} wins. */
    FIRST_TO_FINISH,
    /**
     * {@code team}/{@code individual}: whoever has the most points when the round time budget
     * runs out. Evaluation (given the game has ended) is implemented -- see
     * {@code WinConditionEvaluator#highestScorers} -- but the TRIGGER ("the round time budget
     * ran out, end the game now") is not: no automatic step/deadline-driven FSM advance exists
     * anywhere in this codebase yet (same documented gap as {@code TeacherCommand.NEXT_STEP},
     * plan.md Task 11 note). Today this win condition is only evaluated when something else ends
     * the game (a {@code TeacherCommand.END_GAME}), not automatically on a clock.
     */
    MOST_POINTS_WHEN_TIME_UP
}
