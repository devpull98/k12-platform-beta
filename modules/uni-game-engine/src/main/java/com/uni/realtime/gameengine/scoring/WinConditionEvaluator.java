package com.uni.realtime.gameengine.scoring;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P2 Task 23 (INCLASS-GAME-001-v2.1 §3.2): pure win-condition logic, deliberately free of
 * {@code RoomState}/actor/Pekko dependencies (same "rules stay testable without an actor system"
 * reasoning {@code RoomState} itself already follows). {@code RoomState} calls these, it does not
 * duplicate this logic inline.
 */
public final class WinConditionEvaluator {

    private WinConditionEvaluator() {}

    /** {@code progress_completed}: whole-room progress reached {@code progress_target}. */
    public static boolean progressCompleted(int currentProgress, int progressTarget) {
        return progressTarget > 0 && currentProgress >= progressTarget;
    }

    /**
     * {@code first_to_finish}: this one team's progress just reached {@code progress_target}.
     * Same predicate shape as {@link #progressCompleted} (a target reached is a target reached,
     * whole-room or per-team) -- kept as a separate method name because the two conditions read
     * different counters in {@code RoomState} and a shared name would blur which is which at the
     * call site.
     */
    public static boolean firstToFinish(int teamProgress, int progressTarget) {
        return progressTarget > 0 && teamProgress >= progressTarget;
    }

    /**
     * {@code most_points_when_time_up}: every key (team_id or student_id) tied for the highest
     * score, sorted for a deterministic result. Empty input -> empty output. See
     * {@link #singleHighestScorer} for the common case of wanting just one winner.
     */
    public static List<String> highestScorers(Map<String, Integer> scoreByKey) {
        if (scoreByKey.isEmpty()) {
            return List.of();
        }
        int max = scoreByKey.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        return scoreByKey.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * {@link #highestScorers}, collapsed to a single winner -- empty when there is a tie (an
     * honest "no single winner" rather than an arbitrary pick) or no scores at all.
     */
    public static Optional<String> singleHighestScorer(Map<String, Integer> scoreByKey) {
        List<String> winners = highestScorers(scoreByKey);
        return winners.size() == 1 ? Optional.of(winners.get(0)) : Optional.empty();
    }
}
