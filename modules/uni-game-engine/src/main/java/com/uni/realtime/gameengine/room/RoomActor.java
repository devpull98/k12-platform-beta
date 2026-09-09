package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.TickMode;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.events.GameEventPublisher;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.TeamAssignment;
import io.micrometer.core.instrument.Timer;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.apache.pekko.japi.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class RoomActor extends AbstractBehavior<RoomActor.Command> {
    private static final long WATCHDOG_THRESHOLD_MS = 10;
    private static final long MIN_FLUSH_INTERVAL_MS = 200;
    private static final long SNAPSHOT_MIN_INTERVAL_MS = 2_000;

    private static final Logger snapshotLog = LoggerFactory.getLogger(RoomActor.class);

    public sealed interface Command {
    }

    public record StartGame() implements Command {
    }

    public record StartQuestion(String questionId, long durationMs, List<String> correctAnswerIds) implements Command {
    }

    public record JoinRoom(String studentId, String displayName, ActorRef<GameMessage> replyTo) implements Command {
    }

    public record SubmitAnswer(
            String studentId,
            long sequence,
            String questionId,
            List<String> answerIds,
            long clientTimestampMs,
            ActorRef<GameMessage> replyTo) implements Command {
    }

    public record Resync(String studentId, long lastAckedSeq, List<GameMessage> pending,
                         ActorRef<GameMessage> replyTo) implements Command {
    }

    public record UpdateDraft(String studentId, String draftContent) implements Command {
    }

    public record EndGame() implements Command {
    }

    public record StudentDisconnected(String studentId) implements Command {
    }

    public record KickStudent(String studentId) implements Command {
    }

    private enum Flush implements Command {INSTANCE}

    private enum LeaseLost implements Command {INSTANCE}

    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget) {
        return create(roomId, clock, scoreCalculator, engineMetrics, tickMode, broadcastTarget,
                NoopRoomSnapshotStore.INSTANCE, 0L, null);
    }

    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot) {
        return create(roomId, clock, scoreCalculator, engineMetrics, tickMode, broadcastTarget,
                snapshotStore, epoch, restoreFromSnapshot, MissedStepPolicy.ZERO);
    }

    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot,
            MissedStepPolicy missedStepPolicy) {
        return create(roomId, clock, scoreCalculator, engineMetrics, tickMode, broadcastTarget,
                snapshotStore, epoch, restoreFromSnapshot, missedStepPolicy, null);
    }

    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot,
            MissedStepPolicy missedStepPolicy, GameEventPublisher gameEventPublisher) {
        return create(roomId, clock, scoreCalculator, engineMetrics, tickMode, broadcastTarget,
                snapshotStore, epoch, restoreFromSnapshot, missedStepPolicy, gameEventPublisher,
                GameMode.GAME_MODE_SOLO, 0, List.of(), SharedResourceType.NONE, 0, List.of(),
                ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED);
    }

    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot,
            MissedStepPolicy missedStepPolicy, GameEventPublisher gameEventPublisher,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition) {
        if (tickMode != TickMode.COALESCE) {
            throw new IllegalArgumentException(
                    "RoomActor only implements TickMode.COALESCE in Phase 1, got " + tickMode);
        }
        if (missedStepPolicy != MissedStepPolicy.ZERO) {
            throw new IllegalArgumentException(
                    "RoomActor only implements MissedStepPolicy.ZERO in Phase 1, got " + missedStepPolicy);
        }
        return Behaviors.withTimers(timers -> Behaviors.setup(
                context -> new RoomActor(context, timers, roomId, clock, scoreCalculator, engineMetrics,
                        broadcastTarget, snapshotStore, epoch, restoreFromSnapshot, missedStepPolicy, gameEventPublisher,
                        gameMode, progressTarget, progressStages, sharedResourceType, sharedResourcePenalty,
                        teamRosters, scoreAggregation, winCondition)));
    }

    private final String roomId;
    private final Clock clock;
    private final RoomState state;
    private final Timer processingTimer;
    private final EngineMetrics engineMetrics;
    private final TimerScheduler<Command> timers;
    private final ActorRef<GameMessage> broadcastTarget;
    private final RoomSnapshotStore snapshotStore;
    private final long epoch;
    private final GameEventPublisher gameEventPublisher;
    private final ActorRef<Command> self;

    private boolean flushScheduled = false;
    private long lastFlushAtMs = 0;
    private long lastSnapshotAtMs = 0;

    private RoomActor(ActorContext<Command> context, TimerScheduler<Command> timers, String roomId, Clock clock,
                      ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, ActorRef<GameMessage> broadcastTarget,
                      RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot,
                      MissedStepPolicy missedStepPolicy, GameEventPublisher gameEventPublisher,
                      GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
                      SharedResourceType sharedResourceType, int sharedResourcePenalty,
                      List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition) {
        super(context);
        this.timers = timers;
        this.roomId = roomId;
        this.clock = clock;
        this.state = restoreFromSnapshot == null
                ? new RoomState(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget,
                progressStages, sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation,
                winCondition)
                : RoomState.restore(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget,
                progressStages, sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation,
                winCondition, restoreFromSnapshot);
        this.processingTimer = engineMetrics.processingLatencyTimer();
        this.engineMetrics = engineMetrics;
        this.broadcastTarget = broadcastTarget;
        this.snapshotStore = snapshotStore;
        this.gameEventPublisher = gameEventPublisher;
        this.epoch = epoch;
        this.self = context.getSelf();
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartGame.class, watched(this::onStartGame))
                .onMessage(StartQuestion.class, watched(this::onStartQuestion))
                .onMessage(JoinRoom.class, watched(this::onJoinRoom))
                .onMessage(SubmitAnswer.class, watched(this::onSubmitAnswer))
                .onMessage(Resync.class, watched(this::onResync))
                .onMessage(EndGame.class, watched(this::onEndGame))
                .onMessage(StudentDisconnected.class, watched(this::onStudentDisconnected))
                .onMessage(KickStudent.class, watched(this::onKickStudent))
                .onMessage(UpdateDraft.class, watched(this::onUpdateDraft))
                .onMessage(Flush.class, watched(this::onFlush))
                .onMessage(LeaseLost.class, watched(this::onLeaseLost))
                .build();
    }

    private <C extends Command> Function<C, Behavior<Command>> watched(Function<C, Behavior<Command>> handler) {
        return command -> {
            if (command instanceof JoinRoom || command instanceof SubmitAnswer || command instanceof Resync) {
                engineMetrics.recordMessageDequeued();
            }
            long startNanos = System.nanoTime();
            try {
                return handler.apply(command);
            } finally {
                long elapsedNanos = System.nanoTime() - startNanos;
                processingTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
                if (elapsedMillis > WATCHDOG_THRESHOLD_MS) {
                    getContext().getLog().warn(
                            "room {} handle() took {}ms (> {}ms watchdog threshold) processing {}",
                            roomId, elapsedMillis, WATCHDOG_THRESHOLD_MS, command.getClass().getSimpleName());
                }
            }
        };
    }

    private Behavior<Command> onStartGame(StartGame command) {
        state.startGame();
        return this;
    }

    private Behavior<Command> onStartQuestion(StartQuestion command) {
        state.startQuestion(command.questionId(), command.durationMs(), command.correctAnswerIds());
        scheduleFlushIfDirty();
        return this;
    }

    private Behavior<Command> onJoinRoom(JoinRoom command) {
        GameMessage fullSnapshot = state.joinRoom(command.studentId(), command.displayName());
        command.replyTo().tell(fullSnapshot.toBuilder().setStudentId(command.studentId()).build());
        scheduleFlushIfDirty();
        return this;
    }

    private Behavior<Command> onSubmitAnswer(SubmitAnswer command) {
        GameMessage ack = state.submitAnswer(command.studentId(), command.sequence(),
                command.questionId(), command.answerIds());
        command.replyTo().tell(ack);
        scheduleFlushIfDirty();
        publishGameEvent(ack);
        if (ack.getAnswerAck().getAccepted() && state.phase() == GamePhase.FINISHED) {
            broadcastTarget.tell(state.buildGameOver());
            return Behaviors.stopped();
        }
        return this;
    }

    private Behavior<Command> onResync(Resync command) {
        getContext().getLog().info("room {}: RESYNC from {} (client last_acked_seq={}, {} pending)",
                roomId, command.studentId(), command.lastAckedSeq(), command.pending().size());
        for (GameMessage pending : command.pending()) {
            if (pending.getPayloadCase() != GameMessage.PayloadCase.SUBMIT_ANSWER) {
                getContext().getLog().warn("room {}: ignoring non-SUBMIT_ANSWER entry in RESYNC.pending from {}",
                        roomId, command.studentId());
                continue;
            }
            GameMessage ack = state.submitAnswer(command.studentId(), pending.getSequence(),
                    pending.getSubmitAnswer().getQuestionId(), pending.getSubmitAnswer().getAnswerIdsList());
            command.replyTo().tell(ack);
            publishGameEvent(ack);
        }
        scheduleFlushIfDirty();
        command.replyTo().tell(state.resyncSnapshot(command.studentId()));
        return this;
    }

    private void publishGameEvent(GameMessage ack) {
        if (gameEventPublisher != null && ack.getAnswerAck().getAccepted()) {
            gameEventPublisher.publish(roomId, ack.toByteArray());
        }
    }

    private Behavior<Command> onEndGame(EndGame command) {
        state.endGame();
        broadcastTarget.tell(state.buildGameOver());
        return Behaviors.stopped();
    }

    private Behavior<Command> onStudentDisconnected(StudentDisconnected command) {
        state.markDisconnected(command.studentId());
        scheduleFlushIfDirty();
        return this;
    }

    private Behavior<Command> onUpdateDraft(UpdateDraft command) {
        state.updateDraft(command.studentId(), command.draftContent()).forEach(broadcastTarget::tell);
        return this;
    }

    private Behavior<Command> onKickStudent(KickStudent command) {
        state.markDisconnected(command.studentId());
        broadcastTarget.tell(RoomState.buildStudentKicked(roomId, command.studentId()));
        scheduleFlushIfDirty();
        return this;
    }

    private Behavior<Command> onLeaseLost(LeaseLost command) {
        snapshotLog.warn("room {}: stopping -- Hot Snapshot write was fenced, another pod holds a newer epoch than {}",
                roomId, epoch);
        return Behaviors.stopped();
    }

    private Behavior<Command> onFlush(Flush command) {
        flushScheduled = false;
        if (state.isDirty()) {
            doFlush(clock.millis());
        }
        return this;
    }

    private void scheduleFlushIfDirty() {
        if (!state.isDirty()) {
            return;
        }
        long now = clock.millis();
        if (now - lastFlushAtMs >= MIN_FLUSH_INTERVAL_MS) {
            doFlush(now);
        } else if (!flushScheduled) {
            flushScheduled = true;
            timers.startSingleTimer(Flush.INSTANCE, Flush.INSTANCE,
                    Duration.ofMillis(MIN_FLUSH_INTERVAL_MS - (now - lastFlushAtMs)));
        }
    }

    private void doFlush(long now) {
        broadcastTarget.tell(state.flush());
        lastFlushAtMs = now;
        maybeSnapshot(now);
    }

    private void maybeSnapshot(long now) {
        if (now - lastSnapshotAtMs < SNAPSHOT_MIN_INTERVAL_MS) {
            return;
        }
        lastSnapshotAtMs = now;
        Map<String, Long> committedSequenceByStudent = state.lastSeenSequenceSnapshot();
        Optional<byte[]> envelope = SnapshotEnvelope.wrap(epoch, state.serializeSnapshot());
        if (envelope.isEmpty()) {
            snapshotLog.error("room {}: Hot Snapshot exceeds {} bytes, skipping this write",
                    roomId, SnapshotEnvelope.MAX_ENVELOPE_BYTES);
            return;
        }
        CompletableFuture<SnapshotWriteResult> written = snapshotStore.save(roomId, epoch, envelope.get());
        written.exceptionally(ex -> {
            snapshotLog.warn("room {}: Hot Snapshot write failed", roomId, ex);
            return null;
        }).thenAccept(result -> {
            if (result == null) {
                return;
            }
            switch (result) {
                case ACCEPTED -> {
                    broadcastTarget.tell(RoomState.buildCommittedSeq(roomId, committedSequenceByStudent));
                }
                case FENCED -> {
                    self.tell(LeaseLost.INSTANCE);
                }
                case DISABLED -> {
                }
            }
        });
    }
}
