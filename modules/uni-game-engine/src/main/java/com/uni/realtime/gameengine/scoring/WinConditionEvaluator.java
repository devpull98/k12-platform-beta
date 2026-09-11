package com.uni.realtime.gameengine.scoring;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * {@link #singleHighestScorer}, with a tie broken by total response time (PO V2.2 §5.1): among
     * keys tied for the highest score, the one with the LOWEST total response time wins. Still
     * empty if the tie survives the tie-break too (an honest "no single winner", same spirit as
     * {@link #singleHighestScorer} -- PO does not say what to do at that point, so this does not
     * invent a further rule). {@code responseTimeMsByKey} is looked up only for keys already tied
     * on score; a key missing from it is treated as 0ms (fastest possible), matching a team that
     * never got a chance to answer needing no further penalty here.
     */
    public static Optional<String> singleHighestScorerByResponseTime(
            Map<String, Integer> scoreByKey, Map<String, Long> responseTimeMsByKey) {
        List<String> tiedOnScore = highestScorers(scoreByKey);
        if (tiedOnScore.size() <= 1) {
            return tiedOnScore.stream().findFirst();
        }
        long fastest = tiedOnScore.stream()
                .mapToLong(key -> responseTimeMsByKey.getOrDefault(key, 0L))
                .min().orElseThrow();
        List<String> winners = tiedOnScore.stream()
                .filter(key -> responseTimeMsByKey.getOrDefault(key, 0L) == fastest)
                .sorted()
                .toList();
        return winners.size() == 1 ? Optional.of(winners.get(0)) : Optional.empty();
    }
}
