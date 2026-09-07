package com.uni.realtime.engine.room;

import com.uni.realtime.engine.definition.TickMode;
import com.uni.realtime.engine.metrics.EngineMetrics;
import com.uni.realtime.engine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
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

/**
 * One actor per room (2.4): single-threaded, owns every mutable field of the room via
 * {@link RoomState}. This class itself only wires Pekko dispatch and the watchdog (2.5.4) —
 * game rules live in RoomState so they stay testable without an actor system.
 */
public final class RoomActor extends AbstractBehavior<RoomActor.Command> {

    /** Guardrail (2.5.4): warn, never interrupt — a slow handler is a signal, not a fault. */
    private static final long WATCHDOG_THRESHOLD_MS = 10;

    /** ADR-4/§6.2: 200ms is a ceiling on broadcast frequency, never a fixed tick. */
    private static final long MIN_FLUSH_INTERVAL_MS = 200;

    /**
     * Task 14, §4.3 step 5: "định kỳ mỗi 2-3s hoặc sau câu hỏi" -- approximated here as "at most
     * once per this many ms, checked every time a client-facing flush happens" rather than a
     * second, independent timer. During active submission this naturally coalesces multiple
     * flushes into roughly one Hot Snapshot write every ~2s; during a lull, no snapshot is
     * written at all, which is safe (the last one written is still valid, nothing changed).
     */
    private static final long SNAPSHOT_MIN_INTERVAL_MS = 2_000;

    /**
     * Deliberately NOT {@code getContext().getLog()}: {@link RoomSnapshotStore#save} completes
     * on whatever thread the store's async I/O runs on, never this actor's own thread, and
     * Pekko's actor-bound logger is only safe to use from the actor thread itself.
     */
    private static final Logger snapshotLog = LoggerFactory.getLogger(RoomActor.class);

    public sealed interface Command {}

    public record StartGame() implements Command {}

    public record StartQuestion(String questionId, long durationMs, List<String> correctAnswerIds) implements Command {}

    /** Join reply carries the full snapshot directly (§6.2) — it never waits for coalescing. */
    public record JoinRoom(String studentId, String displayName, ActorRef<GameMessage> replyTo) implements Command {}

    /**
     * clientTimestampMs rides along because the real wire envelope carries it (telemetry
     * only, 9.4) — RoomState.submitAnswer never receives it, so it structurally cannot reach
     * the scoring path.
     */
    public record SubmitAnswer(
            String studentId,
            long sequence,
            String questionId,
            List<String> answerIds,
            long clientTimestampMs,
            ActorRef<GameMessage> replyTo) implements Command {}

    public record EndGame() implements Command {}

    /** Internal timer message (ADR-4 pseudocode's {@code Flush.INSTANCE}) — never sent from outside. */
    private enum Flush implements Command { INSTANCE }

    /**
     * @param tickMode must be {@link TickMode#COALESCE} — Phase 1 has no other implementation
     *     (decision #4 in _context.md); {@link com.uni.realtime.engine.definition.DefinitionLoader}
     *     already rejects {@code FIXED} at load time, so reaching here with anything else means a
     *     caller bypassed the loader, and failing fast here catches that.
     * @param broadcastTarget where outbound room broadcasts (delta/full snapshots) go. RoomActor
     *     stays transport-agnostic on purpose (matches {@code replyTo} on {@link SubmitAnswer}) —
     *     wiring this to the real internal frame channel fan-out is Task 13.
     */
    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget) {
        return create(roomId, clock, scoreCalculator, engineMetrics, tickMode, broadcastTarget,
                NoopRoomSnapshotStore.INSTANCE, 0L, null);
    }

    /**
     * Task 14 (Hot Snapshot): the full-featured constructor. Kept separate from the six-arg
     * {@link #create} above rather than replacing it, so every existing caller (tests,
     * {@code RoomSupervisor}, the scheduler spike) that has no snapshot store to offer keeps
     * compiling unchanged -- {@code RoomSupervisor} switching to this overload for real is a
     * follow-up task, not done here (see plan.md Task 14 progress note).
     *
     * @param snapshotStore where this room's Hot Snapshot is persisted. {@link NoopRoomSnapshotStore}
     *     if the caller has none (Phase 1 today, before wiring).
     * @param epoch this room's fencing generation (from {@link RedisLeaseRoomOwnership#epochOf}
     *     at the moment {@code RoomSupervisor} decided to spawn this actor). Fixed for this
     *     actor's whole lifetime -- reacting to losing the lease mid-life (stopping the actor)
     *     is not wired yet, so a stale epoch here would keep being rejected by the store
     *     (§5.8) rather than silently corrupting anything, but the actor itself would not know
     *     to stop.
     * @param restoreFromSnapshot bytes from a prior {@link RoomState#serializeSnapshot()} (via
     *     {@link RoomSnapshotStore#load}) to resume from, or {@code null} for a brand-new room.
     */
    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot) {
        if (tickMode != TickMode.COALESCE) {
            throw new IllegalArgumentException(
                    "RoomActor only implements TickMode.COALESCE in Phase 1, got " + tickMode);
        }
        return Behaviors.withTimers(timers -> Behaviors.setup(
                context -> new RoomActor(context, timers, roomId, clock, scoreCalculator, engineMetrics,
                        broadcastTarget, snapshotStore, epoch, restoreFromSnapshot)));
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

    private boolean flushScheduled = false;
    private long lastFlushAtMs = 0;
    private long lastSnapshotAtMs = 0;

    private RoomActor(ActorContext<Command> context, TimerScheduler<Command> timers, String roomId, Clock clock,
            ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot) {
        super(context);
        this.timers = timers;
        this.roomId = roomId;
        this.clock = clock;
        this.state = restoreFromSnapshot == null
                ? new RoomState(roomId, clock, scoreCalculator)
                : RoomState.restore(roomId, clock, scoreCalculator, restoreFromSnapshot);
        this.processingTimer = engineMetrics.processingLatencyTimer();
        this.engineMetrics = engineMetrics;
        this.broadcastTarget = broadcastTarget;
        this.snapshotStore = snapshotStore;
        this.epoch = epoch;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartGame.class, watched(this::onStartGame))
                .onMessage(StartQuestion.class, watched(this::onStartQuestion))
                .onMessage(JoinRoom.class, watched(this::onJoinRoom))
                .onMessage(SubmitAnswer.class, watched(this::onSubmitAnswer))
                .onMessage(EndGame.class, watched(this::onEndGame))
                .onMessage(Flush.class, watched(this::onFlush))
                .build();
    }

    private <C extends Command> Function<C, Behavior<Command>> watched(Function<C, Behavior<Command>> handler) {
        return command -> {
            // Only commands RoomSupervisor (Task 13) counted as enqueued get decremented here --
            // StartGame/EndGame/Flush were never counted in, so decrementing for them too would
            // run the gauge negative (exactly what EngineMetrics' javadoc warns against).
            if (command instanceof JoinRoom || command instanceof SubmitAnswer) {
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
        // student_id addresses this reply to the joiner alone -- Gateway's EngineResponseRouter
        // treats any server->client message with a non-empty student_id as personal delivery
        // (the same convention AnswerAck already relies on), never a room-wide broadcast. Without
        // this, the join reply travels the same replyTo as a coalescing flush and gets broadcast
        // to the whole room by mistake.
        command.replyTo().tell(fullSnapshot.toBuilder().setStudentId(command.studentId()).build());
        scheduleFlushIfDirty();
        return this;
    }

    private Behavior<Command> onSubmitAnswer(SubmitAnswer command) {
        GameMessage ack = state.submitAnswer(command.studentId(), command.sequence(),
                command.questionId(), command.answerIds());
        command.replyTo().tell(ack);
        scheduleFlushIfDirty();
        return this;
    }

    private Behavior<Command> onEndGame(EndGame command) {
        state.endGame();
        return Behaviors.stopped();
    }

    private Behavior<Command> onFlush(Flush command) {
        flushScheduled = false;
        if (state.isDirty()) {
            doFlush(clock.millis());
        }
        return this;
    }

    /**
     * ADR-4 pseudocode (§6.2): flush immediately once {@link #MIN_FLUSH_INTERVAL_MS} has
     * elapsed since the last one, otherwise arm exactly one single-shot timer for the
     * remainder of the window. A silent room never calls this at all, and a room that calls it
     * without becoming dirty schedules nothing — that is the "0 packets" case.
     */
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

    /**
     * Task 14: fire-and-forget, off the actor's own execution -- {@link RoomSnapshotStore#save}
     * returns a future this method never blocks on, so a slow or unavailable Redis cannot delay
     * the message this flush was already processing (§13.2, ADR-005's spirit extended to the
     * actor dispatcher, not just the Netty EventLoop).
     */
    private void maybeSnapshot(long now) {
        // Prove-it (2026-09-07): removing this gate turned exactly
        // should_notSaveAgain_when_anotherFlushHappensWithinTheMinInterval red (1 expected, got
        // 2) -- confirms the test actually exercises the 2s floor, not passing by coincidence.
        if (now - lastSnapshotAtMs < SNAPSHOT_MIN_INTERVAL_MS) {
            return;
        }
        lastSnapshotAtMs = now;
        // Captured HERE, on this actor's own thread, before handing off to an async write --
        // the completion callback below runs on a foreign thread and must never read `state`
        // again (Task 15, see RoomState.lastSeenSequenceSnapshot's javadoc).
        Map<String, Long> committedSequenceByStudent = state.lastSeenSequenceSnapshot();
        Optional<byte[]> envelope = SnapshotEnvelope.wrap(epoch, state.serializeSnapshot());
        if (envelope.isEmpty()) {
            snapshotLog.error("room {}: Hot Snapshot exceeds {} bytes, skipping this write",
                    roomId, SnapshotEnvelope.MAX_ENVELOPE_BYTES);
            return;
        }
        CompletableFuture<Boolean> written = snapshotStore.save(roomId, epoch, envelope.get());
        written.exceptionally(ex -> {
            snapshotLog.warn("room {}: Hot Snapshot write failed", roomId, ex);
            return null;
        }).thenAccept(accepted -> {
            // Prove-it (2026-09-07): broadcasting unconditionally here turned exactly the 2
            // negative tests (failed write, fenced-out write) red -- confirms both are exercised.
            if (Boolean.TRUE.equals(accepted)) {
                // Task 15 / B2: ANSWER_ACK already went out immediately (optimistic, hot path,
                // unchanged) -- this is the SEPARATE, later signal that tells a client which
                // sequences are now actually safe to discard from its RingBuffer.
                // ActorRef.tell() is thread-safe by design, so calling it from this callback
                // (not the actor's own thread) is fine -- unlike reaching back into `state`.
                broadcastTarget.tell(RoomState.buildCommittedSeq(roomId, committedSequenceByStudent));
            } else if (Boolean.FALSE.equals(accepted)) {
                // debug, not warn: with the Phase 1 default (NoopRoomSnapshotStore, Redis
                // disabled) EVERY flush takes this branch, so warn-level here would spam
                // production logs for entirely expected, by-design behavior. The genuinely
                // actionable case -- a real RedisSnapshotStore rejecting a write because this
                // pod's epoch is stale (another pod holds a newer lease) -- is indistinguishable
                // from "disabled" at this boolean-only interface; watch a dedicated metric
                // (zombie_actor_stopped_total or a Task-14 follow-up) for that signal instead of
                // this log line.
                snapshotLog.debug("room {}: Hot Snapshot write not accepted (epoch {}) -- CommittedSeq withheld for this flush",
                        roomId, epoch);
            }
        });
    }
}
