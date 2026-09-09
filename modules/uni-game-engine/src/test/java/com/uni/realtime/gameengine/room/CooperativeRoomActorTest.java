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
 * P2 Task 25: actor-level FSM verification for {@code GAME_MODE_COOPERATIVE} -- what
 * {@link RoomStateCooperativeModeTest} already proves about {@link RoomState}'s own logic, seen
 * through {@link RoomActor}'s wiring (auto-{@code FINISHED} -> {@code GameOver} broadcast ->
 * actor stop). Uses the P2 Task 25 master {@code RoomActor.create} overload -- {@code
 * RoomSupervisor} does not call it yet (no {@code GameDefinition} source, plan.md P1 Task 11's
 * still-open gap), so this is the only place these actor-level paths run today.
 */
class CooperativeRoomActorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void should_broadcastGameOverAndStopActor_when_progressReachesTarget() {
        TestInbox<GameMessage> broadcastInbox = TestInbox.create();
        BehaviorTestKit<RoomActor.Command> kit = cooperativeRoomActor(broadcastInbox, 1, List.of());
        kit.run(new RoomActor.StartGame());
        kit.run(new RoomActor.StartQuestion("q-1", 25_000, List.of("a")));
        TestInbox<GameMessage> ackInbox = TestInbox.create();

        kit.run(new RoomActor.SubmitAnswer("student-1", 1L, "q-1", List.of("a"), 0L, ackInbox.getRef()));

        assertThat(ackInbox.receiveMessage().getAnswerAck().getAccepted()).isTrue();
        GameMessage gameOver = broadcastInbox.receiveMessage();
        assertThat(gameOver.getType()).isEqualTo(MessageType.GAME_OVER);
        assertThat(gameOver.getGameOver().getReason()).isEqualTo("progress_completed");
        assertThat(gameOver.getGameOver().getWinnerId()).isEmpty();
        assertThat(kit.isAlive()).as("actor must stop itself once the game auto-finishes").isFalse();
    }

    @Test
    void should_notFinish_when_progressBelowTarget() {
        TestInbox<GameMessage> broadcastInbox = TestInbox.create();
        BehaviorTestKit<RoomActor.Command> kit = cooperativeRoomActor(broadcastInbox, 2, List.of());
        kit.run(new RoomActor.StartGame());
        kit.run(new RoomActor.StartQuestion("q-1", 25_000, List.of("a")));
        TestInbox<GameMessage> ackInbox = TestInbox.create();

        kit.run(new RoomActor.SubmitAnswer("student-1", 1L, "q-1", List.of("a"), 0L, ackInbox.getRef()));

        assertThat(broadcastInbox.hasMessages()).as("1/2 progress must not trigger GameOver yet").isFalse();
        assertThat(kit.isAlive()).isTrue();
    }

    @Test
    void should_advanceStageIndex_inTheFullSnapshotOnJoin() {
        // JoinRoom's reply always carries a full snapshot (§6.2), the simplest way to inspect
        // progress/stage_index without needing the flush timer at all.
        TestInbox<GameMessage> broadcastInbox = TestInbox.create();
        List<ProgressStage> stages = List.of(new ProgressStage(0, "a.svg"), new ProgressStage(50, "b.svg"));
        BehaviorTestKit<RoomActor.Command> kit = cooperativeRoomActor(broadcastInbox, 2, stages);
        kit.run(new RoomActor.StartGame());
        kit.run(new RoomActor.StartQuestion("q-1", 25_000, List.of("a")));
        TestInbox<GameMessage> ackInbox = TestInbox.create();
        kit.run(new RoomActor.SubmitAnswer("student-1", 1L, "q-1", List.of("a"), 0L, ackInbox.getRef()));

        TestInbox<GameMessage> joinInbox = TestInbox.create();
        kit.run(new RoomActor.JoinRoom("student-2", "S2", joinInbox.getRef()));

        var progress = joinInbox.receiveMessage().getRoomStateSnapshot().getProgress();
        assertThat(progress.getProgressPercentage()).isEqualTo(50);
        assertThat(progress.getStageIndex()).isEqualTo(1);
    }

    private static BehaviorTestKit<RoomActor.Command> cooperativeRoomActor(
            TestInbox<GameMessage> broadcastInbox, int progressTarget, List<ProgressStage> progressStages) {
        return BehaviorTestKit.create(RoomActor.create("room-101", CLOCK, FormulaScoreCalculator.binaryChoice(),
                new EngineMetrics(new SimpleMeterRegistry()), TickMode.COALESCE, broadcastInbox.getRef(),
                NoopRoomSnapshotStore.INSTANCE, 0L, null, MissedStepPolicy.ZERO, null,
                GameMode.GAME_MODE_COOPERATIVE, progressTarget, progressStages, SharedResourceType.NONE, 0,
                List.of(), ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED));
    }
}
