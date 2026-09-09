package com.uni.realtime.gameengine.scoring;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Task 23 verification: pure win-condition logic, no {@code RoomState}/actor involved.
 */
class WinConditionEvaluatorTest {

    @Test
    void should_reportProgressCompleted_when_currentReachesTarget() {
        assertThat(WinConditionEvaluator.progressCompleted(10, 10)).isTrue();
        assertThat(WinConditionEvaluator.progressCompleted(11, 10)).isTrue();
        assertThat(WinConditionEvaluator.progressCompleted(9, 10)).isFalse();
    }

    @Test
    void should_notReportProgressCompleted_when_targetIsNotPositive() {
        // A misconfigured/zero target must never look "already complete".
        assertThat(WinConditionEvaluator.progressCompleted(0, 0)).isFalse();
    }

    @Test
    void should_reportFirstToFinish_when_teamProgressReachesTarget() {
        assertThat(WinConditionEvaluator.firstToFinish(5, 5)).isTrue();
        assertThat(WinConditionEvaluator.firstToFinish(4, 5)).isFalse();
    }

    @Test
    void should_returnSingleHighestScorer() {
        Map<String, Integer> scores = Map.of("A", 300, "B", 500, "C", 200);

        assertThat(WinConditionEvaluator.highestScorers(scores)).containsExactly("B");
    }

    @Test
    void should_returnAllTiedScorers_inSortedOrder() {
        Map<String, Integer> scores = Map.of("D", 400, "A", 400, "C", 200);

        assertThat(WinConditionEvaluator.highestScorers(scores)).containsExactly("A", "D");
    }

    @Test
    void should_returnEmptyList_when_noScoresGiven() {
        assertThat(WinConditionEvaluator.highestScorers(Map.of())).isEmpty();
    }

    @Test
    void should_treatEmptyList_asNoSingleScorer() {
        assertThat(WinConditionEvaluator.singleHighestScorer(Map.of())).isEmpty();
    }

    @Test
    void should_returnNoSingleScorer_whenTied() {
        assertThat(WinConditionEvaluator.singleHighestScorer(Map.of("A", 100, "B", 100))).isEmpty();
    }

    @Test
    void should_returnTheSingleScorer_whenNotTied() {
        assertThat(WinConditionEvaluator.singleHighestScorer(Map.of("A", 100, "B", 50))).contains("A");
    }
}
