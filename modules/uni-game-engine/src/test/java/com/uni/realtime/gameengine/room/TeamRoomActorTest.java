package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.TickMode;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.TeamAssignment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pekko.actor.testkit.typed.javadsl.BehaviorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestInbox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Task 25: actor-level FSM verification for {@code GAME_MODE_TEAM} -- what
 * {@link RoomStateTeamModeTest}/{@link RoomStateWinConditionTest} already prove about
 * {@link RoomState} in isolation, seen through {@link RoomActor}'s wiring: {@code JoinRoom}
 * reply carries {@code team_id}/roster, {@code UpdateDraft} fans out scoped, {@code
 * first_to_finish} auto-finishes with a {@code GameOver.winner_id} and stops the actor.
 */
class TeamRoomActorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T09:00:00Z"), ZoneOffset.UTC);
    private static final List<TeamAssignment> TWO_TEAMS = List.of(
            team("A", "student-01", "student-02"), team("B", "student-03", "student-04"));

    @Test
    void should_tagJoinReply_withTeamId() {
        TestInbox<GameMessage> broadcastInbox = TestInbox.create();
        BehaviorTestKit<RoomActor.Command> kit = teamRoomActor(broadcastInbox, 5, WinCondition.FIRST_TO_FINISH);
        TestInbox<GameMessage> joinInbox = TestInbox.create();

        kit.run(new RoomActor.JoinRoom("student-01", "S1", joinInbox.getRef()));

        var snapshot = joinInbox.receiveMessage().getRoomStateSnapshot();
        assertThat(snapshot.getPlayers(0).getTeamId()).isEqualTo("A");
        assertThat(snapshot.getTeamsList()).extracting(TeamAssignment::getTeamId).containsExactly("A", "B");
    }

    @Test
    void should_broadcastGameOverWithWinningTeamId_when_firstToFinish() {
        TestInbox<GameMessage> broadcastInbox = TestInbox.create();
        BehaviorTestKit<RoomActor.Command> kit = teamRoomActor(broadcastInbox, 1, WinCondition.FIRST_TO_FINISH);
        kit.run(new RoomActor.StartGame());
        kit.run(new RoomActor.StartQuestion("q-1", 25_000, List.of("a")));
        TestInbox<GameMessage> ackInbox = TestInbox.create();

        kit.run(new RoomActor.SubmitAnswer("student-01", 1L, "q-1", List.of("a"), 0L, ackInbox.getRef()));

        GameMessage gameOver = broadcastInbox.receiveMessage();
        assertThat(gameOver.getType()).isEqualTo(MessageType.GAME_OVER);
        assertThat(gameOver.getGameOver().getReason()).isEqualTo("first_to_finish");
        assertThat(gameOver.getGameOver().getWinnerId()).isEqualTo("A");
        assertThat(kit.isAlive()).isFalse();
    }

    @Test
    void should_fanOutUpdateDraft_toOtherJoinedTeammatesOnly() {
        TestInbox<GameMessage> broadcastInbox = TestInbox.create();
        BehaviorTestKit<RoomActor.Command> kit = teamRoomActor(broadcastInbox, 5, WinCondition.FIRST_TO_FINISH);
        kit.run(new RoomActor.JoinRoom("student-01", "S1", TestInbox.<GameMessage>create().getRef()));
        kit.run(new RoomActor.JoinRoom("student-02", "S2", TestInbox.<GameMessage>create().getRef()));
        kit.run(new RoomActor.JoinRoom("student-03", "S3", TestInbox.<GameMessage>create().getRef())); // Team B

        kit.run(new RoomActor.UpdateDraft("student-01", "Phuong trinh bac 2"));

        // The joins above also flush a ROOM_STATE_SNAPSHOT into broadcastInbox (a real, unrelated
        // side effect of RoomActor's own flush-immediately-if-idle-long-enough rule) -- filter to
        // just the UPDATE_DRAFT messages this test actually cares about.
        List<GameMessage> draftBroadcasts = broadcastInbox.getAllReceived().stream()
                .filter(message -> message.getType() == MessageType.UPDATE_DRAFT)
                .toList();
        assertThat(draftBroadcasts).hasSize(1); // only student-02, never student-03 (Team B)
        assertThat(draftBroadcasts.get(0).getStudentId()).isEqualTo("student-02");
        assertThat(draftBroadcasts.get(0).getDraftUpdate().getDraftContent()).isEqualTo("Phuong trinh bac 2");
    }

    private static TeamAssignment team(String teamId, String... studentIds) {
        return TeamAssignment.newBuilder()
                .setTeamId(teamId)
                .setTeamName("Team " + teamId)
                .addAllStudentIds(List.of(studentIds))
                .build();
    }

    private static BehaviorTestKit<RoomActor.Command> teamRoomActor(
            TestInbox<GameMessage> broadcastInbox, int progressTarget, WinCondition winCondition) {
        return BehaviorTestKit.create(RoomActor.create("room-101", CLOCK, FormulaScoreCalculator.binaryChoice(),
                new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE, broadcastInbox.getRef(),
                NoopRoomSnapshotStore.INSTANCE, 0L, null, MissedStepPolicy.ZERO, null,
                GameMode.GAME_MODE_TEAM, progressTarget, List.<ProgressStage>of(), SharedResourceType.NONE, 0,
                TWO_TEAMS, ScoreAggregation.SUM_ALL, winCondition));
    }
}
