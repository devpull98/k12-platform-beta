package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.PlayerState;
import com.uni.realtime.protocol.RoomStateSnapshot;
import com.uni.realtime.protocol.TeamAssignment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Task 22 (INCLASS-GAME-001-v2.1, BDD: team-speed-race.feature) verification: team rosters
 * (fixed, upstream-decided config -- see {@code GameDefinition.teamRosters}' javadoc for why
 * {@code RoomState} never invents an assignment algorithm), per-team score aggregation, and
 * scoped {@code UPDATE_DRAFT} fan-out to teammates only.
 */
class RoomStateTeamModeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T09:00:00Z"), ZoneOffset.UTC);

    private static final List<TeamAssignment> FOUR_TEAMS_OF_THREE = List.of(
            team("A", "student-01", "student-02", "student-03"),
            team("B", "student-04", "student-05", "student-06"),
            team("C", "student-07", "student-08", "student-09"),
            team("D", "student-10", "student-11", "student-12"));

    @Test
    void should_tagEachPlayerState_withItsConfiguredTeamId() {
        RoomState room = teamRoom(FOUR_TEAMS_OF_THREE, 5, ScoreAggregation.SUM_ALL);

        GameMessage joinedA = room.joinRoom("student-01", "S1");
        GameMessage joinedB = room.joinRoom("student-04", "S4");

        assertThat(joinedA.getRoomStateSnapshot().getPlayers(0).getTeamId()).isEqualTo("A");
        assertThat(playerOf(joinedB.getRoomStateSnapshot(), "student-04").getTeamId()).isEqualTo("B");
    }

    @Test
    void should_broadcastAllFourRosters_onFullSnapshot() {
        RoomState room = teamRoom(FOUR_TEAMS_OF_THREE, 5, ScoreAggregation.SUM_ALL);
        room.joinRoom("student-01", "S1");

        RoomStateSnapshot snapshot = room.flush().getRoomStateSnapshot();

        assertThat(snapshot.getTeamsList()).hasSize(4);
        assertThat(teamOf(snapshot, "A").getStudentIdsList())
                .containsExactly("student-01", "student-02", "student-03");
    }

    @Test
    void should_aggregateTeamScore_bySumAll() {
        RoomState room = teamRoom(FOUR_TEAMS_OF_THREE, 5, ScoreAggregation.SUM_ALL);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-02", "S2");
        room.joinRoom("student-03", "S3");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a")); // +100
        room.submitAnswer("student-02", 1L, "q-1", List.of("a")); // +100
        room.submitAnswer("student-03", 1L, "q-1", List.of("wrong")); // +0

        RoomStateSnapshot snapshot = room.flush().getRoomStateSnapshot();
        assertThat(teamOf(snapshot, "A").getTeamScore()).isEqualTo(200);
    }

    @Test
    void should_aggregateTeamScore_byAverage() {
        RoomState room = teamRoom(FOUR_TEAMS_OF_THREE, 5, ScoreAggregation.AVERAGE);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-02", "S2");
        room.joinRoom("student-03", "S3");
        room.startGame();
        room.startQuestion("q-1", 25_000, List.of("a"));
        room.submitAnswer("student-01", 1L, "q-1", List.of("a")); // +100
        room.submitAnswer("student-02", 1L, "q-1", List.of("a")); // +100
        room.submitAnswer("student-03", 1L, "q-1", List.of("wrong")); // +0

        RoomStateSnapshot snapshot = room.flush().getRoomStateSnapshot();
        assertThat(teamOf(snapshot, "A").getTeamScore()).isEqualTo(200 / 3); // floor(66.67) = 66
    }

    @Test
    void should_broadcastDraftUpdate_onlyToOtherJoinedTeamMembers() {
        RoomState room = teamRoom(FOUR_TEAMS_OF_THREE, 5, ScoreAggregation.SUM_ALL);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-02", "S2");
        room.joinRoom("student-03", "S3");
        room.joinRoom("student-04", "S4"); // Team B -- must never receive Team A's draft

        List<GameMessage> outbound = room.updateDraft("student-01", "Phuong trinh bac 2");

        assertThat(outbound).extracting(GameMessage::getStudentId)
                .containsExactlyInAnyOrder("student-02", "student-03");
        assertThat(outbound).allSatisfy(message -> {
            assertThat(message.getType()).isEqualTo(com.uni.realtime.protocol.MessageType.UPDATE_DRAFT);
            assertThat(message.getDraftUpdate().getTeamId()).isEqualTo("A");
            assertThat(message.getDraftUpdate().getStudentId()).isEqualTo("student-01");
            assertThat(message.getDraftUpdate().getDraftContent()).isEqualTo("Phuong trinh bac 2");
        });
    }

    @Test
    void should_excludeNotYetJoinedTeammates_fromDraftFanOut() {
        RoomState room = teamRoom(FOUR_TEAMS_OF_THREE, 5, ScoreAggregation.SUM_ALL);
        room.joinRoom("student-01", "S1");
        room.joinRoom("student-02", "S2"); // student-03 (same team) never joins

        List<GameMessage> outbound = room.updateDraft("student-01", "draft");

        assertThat(outbound).extracting(GameMessage::getStudentId).containsExactly("student-02");
    }

    @Test
    void should_returnEmptyList_when_soloRoomReceivesUpdateDraft() {
        RoomState room = new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice());
        room.joinRoom("student-01", "S1");

        assertThat(room.updateDraft("student-01", "draft")).isEmpty();
    }

    @Test
    void should_returnEmptyList_when_senderIsNotOnAnyRoster() {
        RoomState room = teamRoom(FOUR_TEAMS_OF_THREE, 5, ScoreAggregation.SUM_ALL);
        room.joinRoom("student-99", "Ghost"); // not in any configured roster

        assertThat(room.updateDraft("student-99", "draft")).isEmpty();
    }

    private static PlayerState playerOf(RoomStateSnapshot snapshot, String studentId) {
        return snapshot.getPlayersList().stream()
                .filter(p -> p.getStudentId().equals(studentId))
                .findFirst().orElseThrow();
    }

    private static TeamAssignment teamOf(RoomStateSnapshot snapshot, String teamId) {
        return snapshot.getTeamsList().stream()
                .filter(t -> t.getTeamId().equals(teamId))
                .findFirst().orElseThrow();
    }

    private static TeamAssignment team(String teamId, String... studentIds) {
        return TeamAssignment.newBuilder()
                .setTeamId(teamId)
                .setTeamName("Team " + teamId)
                .addAllStudentIds(List.of(studentIds))
                .build();
    }

    private static RoomState teamRoom(List<TeamAssignment> teamRosters, int progressTarget,
            ScoreAggregation scoreAggregation) {
        return new RoomState("room-1", CLOCK, FormulaScoreCalculator.binaryChoice(), MissedStepPolicy.ZERO,
                GameMode.GAME_MODE_TEAM, progressTarget, List.<ProgressStage>of(), SharedResourceType.NONE, 0,
                teamRosters, scoreAggregation);
    }
}
