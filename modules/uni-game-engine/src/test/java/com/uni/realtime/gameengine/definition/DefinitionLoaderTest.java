package com.uni.realtime.gameengine.definition;

import com.uni.realtime.protocol.GameMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 11 verification (plan.md): every guardrail fails at {@code load()} itself -- never
 * discovered later at runtime.
 */
class DefinitionLoaderTest {

    private final DefinitionLoader loader = new DefinitionLoader();

    @Test
    void should_returnDefinition_when_linearQuizIsValid() throws DefinitionRejectedException {
        GameDefinition definition = linearQuiz();

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_stepGraphHasACycle() {
        // q1 -> q2 -> q1: a cycle, not a valid DAG, must never reach the game engine.
        GameDefinition cyclic = new GameDefinition(
                List.of(
                        new Step("q1", 25_000, List.of("q2")),
                        new Step("q2", 25_000, List.of("q1"))),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(cyclic))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void should_rejectAtLoadTime_when_cycleIsNotOnTheStartPath() {
        // start -> q1 (a dead end), but q2 <-> q3 cycle elsewhere in the same definition.
        GameDefinition cyclic = new GameDefinition(
                List.of(
                        new Step("start", 25_000, List.of("q1")),
                        new Step("q1", 25_000, List.of()),
                        new Step("q2", 25_000, List.of("q3")),
                        new Step("q3", 25_000, List.of("q2"))),
                "start",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(cyclic)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_tickModeIsFixed() {
        GameDefinition fixedTick = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.FIXED,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(fixedTick))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("FIXED");
    }

    @Test
    void should_rejectAtLoadTime_when_missedStepPolicyIsSkip() {
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.SKIP,
                50);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("SKIP");
    }

    @Test
    void should_rejectAtLoadTime_when_missedStepPolicyIsAllowLate() {
        // system-architecture.md §4.8: ALLOW_LATE also blows the < 5 KB Hot Snapshot budget --
        // not just "unimplemented", actively unsafe to allow through.
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ALLOW_LATE,
                50);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("ALLOW_LATE");
    }

    @Test
    void should_rejectAtLoadTime_when_startStepIdIsUndefined() {
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "does-not-exist",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_aStepPointsToAnUndefinedStep() {
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of("ghost"))),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                50);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_maxTransitionsIsNotPositive() {
        GameDefinition definition = new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Constant(100),
                MissedStepPolicy.ZERO,
                0);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    @Test
    void should_rejectAtLoadTime_when_stepsIsEmpty() {
        GameDefinition definition = new GameDefinition(
                List.of(), "q1", TickMode.COALESCE, new ScoringFormula.Constant(100), MissedStepPolicy.ZERO, 50);

        assertThatThrownBy(() -> loader.load(definition)).isInstanceOf(DefinitionRejectedException.class);
    }

    // ------------------------- P2 Task 21 (INCLASS-GAME-001) -------------------------

    @Test
    void should_returnDefinition_when_cooperativeModeIsValid() throws DefinitionRejectedException {
        GameDefinition definition = cooperativeQuiz(10, SharedResourceType.TIME, 5);

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_cooperativeModeHasNonPositiveProgressTarget() {
        GameDefinition definition = cooperativeQuiz(0, SharedResourceType.NONE, 0);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("progress_target");
    }

    @Test
    void should_rejectAtLoadTime_when_progressStageMilestoneOutOfRange() {
        GameDefinition definition = withProgressStages(cooperativeQuiz(10, SharedResourceType.NONE, 0),
                List.of(new ProgressStage(0, "a.svg"), new ProgressStage(150, "b.svg")));

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("milestone");
    }

    @Test
    void should_rejectAtLoadTime_when_progressStagesNotAscending() {
        GameDefinition definition = withProgressStages(cooperativeQuiz(10, SharedResourceType.NONE, 0),
                List.of(new ProgressStage(70, "a.svg"), new ProgressStage(30, "b.svg")));

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("ascending");
    }

    @Test
    void should_returnDefinition_when_maxPlayersIsUnspecified() throws DefinitionRejectedException {
        // P2 Task 29: 0 is the "not specified" sentinel -- must not be rejected as out of [1, 12].
        GameDefinition definition = linearQuiz();

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_maxPlayersIsAboveTwelve() {
        GameDefinition definition = withMaxPlayers(cooperativeQuiz(10, SharedResourceType.NONE, 0), 13);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("max_players");
    }

    @Test
    void should_rejectAtLoadTime_when_maxPlayersIsNegative() {
        GameDefinition definition = withMaxPlayers(cooperativeQuiz(10, SharedResourceType.NONE, 0), -1);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("max_players");
    }

    @Test
    void should_returnDefinition_when_maxPlayersIsWithinRange() throws DefinitionRejectedException {
        GameDefinition definition = withMaxPlayers(cooperativeQuiz(10, SharedResourceType.NONE, 0), 12);

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_questionHasFewerThanTwoOptions() {
        GameDefinition definition = withQuestions(cooperativeQuiz(10, SharedResourceType.NONE, 0),
                List.of(new Question("2+2=?", List.of("4"), 0)));

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("at least 2 options");
    }

    @Test
    void should_rejectAtLoadTime_when_questionCorrectOptionIndexOutOfRange() {
        GameDefinition definition = withQuestions(cooperativeQuiz(10, SharedResourceType.NONE, 0),
                List.of(new Question("2+2=?", List.of("3", "4"), 2)));

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("correct_option_index");
    }

    @Test
    void should_returnDefinition_when_questionsAreWellFormed() throws DefinitionRejectedException {
        GameDefinition definition = withQuestions(cooperativeQuiz(10, SharedResourceType.NONE, 0),
                List.of(new Question("2+2=?", List.of("3", "4"), 1)));

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_sharedResourceTimeHasNonPositivePenalty() {
        GameDefinition definition = cooperativeQuiz(10, SharedResourceType.TIME, 0);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("penalty");
    }

    @Test
    void should_rejectAtLoadTime_when_sharedResourceTypeIsLives() {
        // RoomState has no lives-tracking implementation yet (no spec'd starting count either) --
        // same fail-fast-until-implemented posture as missed_step_policy SKIP/ALLOW_LATE.
        GameDefinition definition = cooperativeQuiz(10, SharedResourceType.LIVES, 1);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("LIVES");
    }

    @Test
    void should_rejectAtLoadTime_when_gameModeIsIndividual() {
        // No RoomState individual-mode implementation -- out of Task 22's scope (team mode only).
        GameDefinition definition = teamQuiz(twoTeamsOfThree(), 5, ScoreAggregation.SUM_ALL);
        GameDefinition individual = new GameDefinition(definition.steps(), definition.startStepId(),
                definition.tickMode(), definition.scoringFormula(), definition.missedStepPolicy(),
                definition.maxTransitions(), GameMode.GAME_MODE_INDIVIDUAL, definition.progressTarget(),
                definition.progressStages(), definition.sharedResourceType(), definition.sharedResourcePenalty(),
                definition.teamRosters(), definition.scoreAggregation());

        assertThatThrownBy(() -> loader.load(individual))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("INDIVIDUAL");
    }

    // ------------------------- P2 Task 22 (INCLASS-GAME-001) -------------------------

    @Test
    void should_returnDefinition_when_teamModeIsValid() throws DefinitionRejectedException {
        GameDefinition definition = teamQuiz(twoTeamsOfThree(), 5, ScoreAggregation.SUM_ALL);

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_onlyOneTeamRosterIsGiven() {
        GameDefinition definition = teamQuiz(List.of(team("A", "student-01")), 5, ScoreAggregation.SUM_ALL);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("team_count");
    }

    @Test
    void should_rejectAtLoadTime_when_moreThanFourTeamRostersAreGiven() {
        GameDefinition definition = teamQuiz(List.of(
                team("A", "s1"), team("B", "s2"), team("C", "s3"), team("D", "s4"), team("E", "s5")),
                5, ScoreAggregation.SUM_ALL);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("team_count");
    }

    @Test
    void should_rejectAtLoadTime_when_aTeamRosterIsEmpty() {
        GameDefinition definition = teamQuiz(List.of(
                team("A", "student-01", "student-02"), team("B")), 5, ScoreAggregation.SUM_ALL);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("no members");
    }

    @Test
    void should_rejectAtLoadTime_when_aStudentAppearsInTwoTeamRosters() {
        GameDefinition definition = teamQuiz(List.of(
                team("A", "student-01", "student-02"), team("B", "student-02", "student-03")),
                5, ScoreAggregation.SUM_ALL);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("more than one team");
    }

    @Test
    void should_rejectAtLoadTime_when_teamModeHasNonPositiveProgressTarget() {
        GameDefinition definition = teamQuiz(twoTeamsOfThree(), 0, ScoreAggregation.SUM_ALL);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("progress_target");
    }

    // ------------------------- P2 Task 23 (INCLASS-GAME-001) -------------------------

    @Test
    void should_returnDefinition_when_teamModeUsesMostPointsWhenTimeUp() throws DefinitionRejectedException {
        GameDefinition base = teamQuiz(twoTeamsOfThree(), 5, ScoreAggregation.SUM_ALL);
        GameDefinition definition = new GameDefinition(base.steps(), base.startStepId(), base.tickMode(),
                base.scoringFormula(), base.missedStepPolicy(), base.maxTransitions(), base.gameMode(),
                base.progressTarget(), base.progressStages(), base.sharedResourceType(),
                base.sharedResourcePenalty(), base.teamRosters(), base.scoreAggregation(),
                WinCondition.MOST_POINTS_WHEN_TIME_UP);

        assertThat(loader.load(definition)).isEqualTo(definition);
    }

    @Test
    void should_rejectAtLoadTime_when_teamModeUsesProgressCompleted() {
        GameDefinition base = teamQuiz(twoTeamsOfThree(), 5, ScoreAggregation.SUM_ALL);
        GameDefinition definition = new GameDefinition(base.steps(), base.startStepId(), base.tickMode(),
                base.scoringFormula(), base.missedStepPolicy(), base.maxTransitions(), base.gameMode(),
                base.progressTarget(), base.progressStages(), base.sharedResourceType(),
                base.sharedResourcePenalty(), base.teamRosters(), base.scoreAggregation(),
                WinCondition.PROGRESS_COMPLETED);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("win_condition");
    }

    @Test
    void should_rejectAtLoadTime_when_cooperativeModeUsesFirstToFinish() {
        GameDefinition base = cooperativeQuiz(10, SharedResourceType.NONE, 0);
        GameDefinition definition = new GameDefinition(base.steps(), base.startStepId(), base.tickMode(),
                base.scoringFormula(), base.missedStepPolicy(), base.maxTransitions(), base.gameMode(),
                base.progressTarget(), base.progressStages(), base.sharedResourceType(),
                base.sharedResourcePenalty(), base.teamRosters(), base.scoreAggregation(),
                WinCondition.FIRST_TO_FINISH);

        assertThatThrownBy(() -> loader.load(definition))
                .isInstanceOf(DefinitionRejectedException.class)
                .hasMessageContaining("win_condition");
    }

    private static List<com.uni.realtime.protocol.TeamAssignment> twoTeamsOfThree() {
        return List.of(
                team("A", "student-01", "student-02", "student-03"),
                team("B", "student-04", "student-05", "student-06"));
    }

    private static com.uni.realtime.protocol.TeamAssignment team(String teamId, String... studentIds) {
        return com.uni.realtime.protocol.TeamAssignment.newBuilder()
                .setTeamId(teamId)
                .setTeamName("Team " + teamId)
                .addAllStudentIds(List.of(studentIds))
                .build();
    }

    private static GameDefinition teamQuiz(List<com.uni.realtime.protocol.TeamAssignment> teamRosters,
            int progressTarget, ScoreAggregation scoreAggregation) {
        return new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Multiply(new ScoringFormula.IsCorrect(), new ScoringFormula.Constant(100)),
                MissedStepPolicy.ZERO,
                50,
                GameMode.GAME_MODE_TEAM,
                progressTarget,
                List.of(),
                SharedResourceType.NONE,
                0,
                teamRosters,
                scoreAggregation);
    }

    private static GameDefinition cooperativeQuiz(int progressTarget, SharedResourceType sharedResourceType,
            int sharedResourcePenalty) {
        return new GameDefinition(
                List.of(new Step("q1", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Multiply(new ScoringFormula.IsCorrect(), new ScoringFormula.Constant(100)),
                MissedStepPolicy.ZERO,
                50,
                GameMode.GAME_MODE_COOPERATIVE,
                progressTarget,
                List.of(),
                sharedResourceType,
                sharedResourcePenalty);
    }

    private static GameDefinition withProgressStages(GameDefinition base, List<ProgressStage> stages) {
        return new GameDefinition(base.steps(), base.startStepId(), base.tickMode(), base.scoringFormula(),
                base.missedStepPolicy(), base.maxTransitions(), base.gameMode(), base.progressTarget(), stages,
                base.sharedResourceType(), base.sharedResourcePenalty());
    }

    private static GameDefinition withMaxPlayers(GameDefinition base, int maxPlayers) {
        return new GameDefinition(base.steps(), base.startStepId(), base.tickMode(), base.scoringFormula(),
                base.missedStepPolicy(), base.maxTransitions(), base.gameMode(), base.progressTarget(),
                base.progressStages(), base.sharedResourceType(), base.sharedResourcePenalty(), base.teamRosters(),
                base.scoreAggregation(), base.winCondition(), maxPlayers, base.questions(),
                base.roundTimeLimitSeconds(), base.lateJoinPolicy(), base.teamAssignmentMode(),
                base.introNarrative(), base.progressDisplayMode());
    }

    private static GameDefinition withQuestions(GameDefinition base, List<Question> questions) {
        return new GameDefinition(base.steps(), base.startStepId(), base.tickMode(), base.scoringFormula(),
                base.missedStepPolicy(), base.maxTransitions(), base.gameMode(), base.progressTarget(),
                base.progressStages(), base.sharedResourceType(), base.sharedResourcePenalty(), base.teamRosters(),
                base.scoreAggregation(), base.winCondition(), base.maxPlayers(), questions,
                base.roundTimeLimitSeconds(), base.lateJoinPolicy(), base.teamAssignmentMode(),
                base.introNarrative(), base.progressDisplayMode());
    }

    private static GameDefinition linearQuiz() {
        return new GameDefinition(
                List.of(
                        new Step("q1", 25_000, List.of("q2")),
                        new Step("q2", 25_000, List.of("q3")),
                        new Step("q3", 25_000, List.of())),
                "q1",
                TickMode.COALESCE,
                new ScoringFormula.Add(
                        new ScoringFormula.Multiply(new ScoringFormula.IsCorrect(), new ScoringFormula.Constant(100)),
                        new ScoringFormula.Constant(0)),
                MissedStepPolicy.ZERO,
                50);
    }
}
