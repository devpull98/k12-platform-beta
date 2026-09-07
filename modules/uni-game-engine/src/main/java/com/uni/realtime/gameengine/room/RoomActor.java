package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.TickMode;
import com.uni.realtime.gameengine.events.GameEventPublisher;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
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

    /**
     * PH-3 / §9.3: sent after a client reconnects, carrying what it believes it has (proto
     * {@code Resync.last_acked_seq}/{@code pending}). {@code pending} entries are replayed
     * through the SAME {@link RoomState#submitAnswer} dedupe/scoring path {@link SubmitAnswer}
     * already uses -- {@code sequence <= last_seen} replays the stored ack (§5.2), so an
     * already-scored replay is structurally harmless with no new dedupe logic here.
     * {@code lastAckedSeq} is logged for diagnostics only, never used for correctness: the
     * server's own {@code lastSeenSequence} table is authoritative regardless of what the
     * client believes it has. No new actor FSM state -- {@code RESYNCING} is out of scope for
     * Phase 1 (system-architecture.md: "GĐ1: FSM chỉ có LOBBY → PLAYING → FINISHED, RESYNCING
     * chưa cần") -- this is handled as an ordinary message.
     */
    public record Resync(String studentId, long lastAckedSeq, List<GameMessage> pending,
            ActorRef<GameMessage> replyTo) implements Command {}

    public record EndGame() implements Command {}

    /** Internal timer message (ADR-4 pseudocode's {@code Flush.INSTANCE}) — never sent from outside. */
    private enum Flush implements Command { INSTANCE }

    /**
     * Task 14 follow-up (zombie-actor fix): sent to {@link #self} when a Hot Snapshot write
     * comes back fenced ({@code snapshotStore.save} resolves {@code false}) -- a monotonically
     * increasing epoch counter rejected this actor's epoch, which can only mean another pod
     * already won a newer lease for this room. Not a transient condition (the counter never
     * moves backward), so one occurrence is a definitive, permanent signal to stop -- no
     * debounce/retry needed. Never sent from outside.
     */
    private enum LeaseLost implements Command { INSTANCE }

    /**
     * @param tickMode must be {@link TickMode#COALESCE} — Phase 1 has no other implementation
     *     (decision #4 in _context.md); {@link com.uni.realtime.gameengine.definition.DefinitionLoader}
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
     * @param epoch this room's fencing generation (from {@link LeaseBasedRoomOwnership#epochOf}
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
        return create(roomId, clock, scoreCalculator, engineMetrics, tickMode, broadcastTarget,
                snapshotStore, epoch, restoreFromSnapshot, MissedStepPolicy.ZERO);
    }

    /**
     * Task 17 (B4): the true master constructor, adding {@code missedStepPolicy} on top of
     * Task 14's nine-arg overload above (which now just delegates here with
     * {@link MissedStepPolicy#ZERO}) -- same additive-overload shape as Task 14 used for
     * {@code snapshotStore}/{@code epoch}/{@code restoreFromSnapshot}, so every existing caller
     * with no policy to offer keeps compiling unchanged.
     *
     * @param missedStepPolicy must be {@link MissedStepPolicy#ZERO} — same fail-fast shape as
     *     {@code tickMode} above. {@link com.uni.realtime.gameengine.definition.DefinitionLoader}
     *     already rejects {@code SKIP}/{@code ALLOW_LATE} at load time; failing fast here too
     *     catches a caller that bypassed the loader. Not stored as a field: like {@code tickMode},
     *     its only job in Phase 1 is this gate -- there is no late-join flow yet for a
     *     non-{@code ZERO} policy to change the behavior of (see plan.md Task 17's "Chưa làm").
     */
    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot,
            MissedStepPolicy missedStepPolicy) {
        return create(roomId, clock, scoreCalculator, engineMetrics, tickMode, broadcastTarget,
                snapshotStore, epoch, restoreFromSnapshot, missedStepPolicy, null);
    }

    /**
     * Task 18 (§9.3 Rủi ro 6): the true master constructor, adding {@code gameEventPublisher} on
     * top of Task 17's ten-arg overload above -- same additive shape, so every existing caller
     * with no publisher to offer keeps compiling unchanged.
     *
     * @param gameEventPublisher where {@code SubmitAnswer} outcomes are shipped for analytics/
     *     audit (§4.3 step 5), or {@code null} to skip publishing entirely (Phase 1 default,
     *     before wiring). Nullable rather than a {@code NoopGameEventPublisher} because
     *     {@link GameEventPublisher} owns a real background thread -- a no-op instance would
     *     still have to start and stop one for nothing.
     */
    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
            TickMode tickMode, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot,
            MissedStepPolicy missedStepPolicy, GameEventPublisher gameEventPublisher) {
        if (tickMode != TickMode.COALESCE) {
            throw new IllegalArgumentException(
                    "RoomActor only implements TickMode.COALESCE in Phase 1, got " + tickMode);
        }
        // Prove-it (2026-09-07): temporarily disabling this guard turned exactly
        // should_rejectNonZeroMissedStepPolicy_when_creatingRoomActor_because_onlyZeroIsImplemented red.
        if (missedStepPolicy != MissedStepPolicy.ZERO) {
            throw new IllegalArgumentException(
                    "RoomActor only implements MissedStepPolicy.ZERO in Phase 1, got " + missedStepPolicy);
        }
        return Behaviors.withTimers(timers -> Behaviors.setup(
                context -> new RoomActor(context, timers, roomId, clock, scoreCalculator, engineMetrics,
                        broadcastTarget, snapshotStore, epoch, restoreFromSnapshot, gameEventPublisher)));
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
    /**
     * Captured once here, on the actor's own thread (construction always runs there) --
     * {@code ActorRef.tell()} is safe from any thread, unlike most of {@code ActorContext}, so
     * this is what {@link #maybeSnapshot} uses to signal {@link LeaseLost} from its async
     * callback instead of touching {@code getContext()} off-thread.
     */
    private final ActorRef<Command> self;

    private boolean flushScheduled = false;
    private long lastFlushAtMs = 0;
    private long lastSnapshotAtMs = 0;

    private RoomActor(ActorContext<Command> context, TimerScheduler<Command> timers, String roomId, Clock clock,
            ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, ActorRef<GameMessage> broadcastTarget,
            RoomSnapshotStore snapshotStore, long epoch, byte[] restoreFromSnapshot,
            GameEventPublisher gameEventPublisher) {
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
                .onMessage(Flush.class, watched(this::onFlush))
                .onMessage(LeaseLost.class, watched(this::onLeaseLost))
                .build();
    }

    private <C extends Command> Function<C, Behavior<Command>> watched(Function<C, Behavior<Command>> handler) {
        return command -> {
            // Only commands RoomSupervisor (Task 13) counted as enqueued get decremented here --
            // StartGame/EndGame/Flush were never counted in, so decrementing for them too would
            // run the gauge negative (exactly what EngineMetrics' javadoc warns against).
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
        publishGameEvent(ack);
        return this;
    }

    /**
     * PH-3 / §9.3: replays every buffered submission the client believes never got acked, each
     * through the exact same {@link RoomState#submitAnswer} call {@link #onSubmitAnswer} uses --
     * a sequence the room already scored comes back as the stored ack (§5.2), never re-scored.
     * Ends with one full, personally-addressed {@link RoomStateSnapshot} so a reconnecting
     * client has the whole room again, not just acks for what it happened to buffer.
     */
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

    /**
     * Task 18 (§9.3 Rủi ro 6, §4.3 step 5): ships the {@code AnswerAck} itself as the event
     * payload -- no separate {@code GameEvent} schema exists yet, and this is the exact record
     * of a scored submission §4.3 describes pushing to Kafka, so reusing it avoids inventing a
     * business decision this task isn't scoped to make. Partitioned by {@code roomId}, not
     * {@code session_id} as §4.3/§7.6 name it -- {@code session_id} does not exist anywhere in
     * this data model (only {@code room_id}/{@code student_id} do); revisit this key once a real
     * session concept exists. {@code gameEventPublisher} is {@code null} until wired (Phase 1
     * default), and {@link GameEventPublisher#publish} never blocks regardless (Task 18).
     */
    private void publishGameEvent(GameMessage ack) {
        // Prove-it (2026-09-07): dropping the accepted check turned exactly
        // should_notPublish_when_theAnswerIsRejected red -- confirms it's exercised.
        if (gameEventPublisher != null && ack.getAnswerAck().getAccepted()) {
            gameEventPublisher.publish(roomId, ack.toByteArray());
        }
    }

    private Behavior<Command> onEndGame(EndGame command) {
        state.endGame();
        return Behaviors.stopped();
    }

    /**
     * Zombie-actor fix: a fenced Hot Snapshot write means another pod already won a newer lease
     * for this room (§5.8) -- this pod is no longer the legitimate owner, so it must stop rather
     * than keep answering as if it were. {@code RoomSupervisor} already {@code watchWith}s every
     * room it spawns (originally for {@code EndGame}), so stopping here is enough: cleanup of
     * {@code roomsByRoomId}/{@code subscribersByRoom} happens the same way it already does today.
     */
    private Behavior<Command> onLeaseLost(LeaseLost command) {
        // Prove-it (2026-09-07): temporarily returning `this` instead of stopped() turned
        // exactly should_stopTheActor_when_theSnapshotWriteIsFencedOut red.
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
     * returns a future this method never blocks on, so a slow or unavailable room store cannot delay
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
        CompletableFuture<SnapshotWriteResult> written = snapshotStore.save(roomId, epoch, envelope.get());
        written.exceptionally(ex -> {
            // A network/timeout failure is NOT a definitive signal -- unlike FENCED (a
            // monotonic counter that never moves backward), an exception could just as easily
            // be a transient blip. Never treated as a lease loss, or a flaky store connection
            // would wrongly kill perfectly healthy rooms.
            snapshotLog.warn("room {}: Hot Snapshot write failed", roomId, ex);
            return null;
        }).thenAccept(result -> {
            if (result == null) {
                return;
            }
            // Prove-it (2026-09-07): broadcasting unconditionally regardless of `result` turned
            // exactly should_notBroadcastCommittedSeq_whenTheSnapshotWriteFails/...WhenTheWriteIsFencedOut red.
            switch (result) {
                case ACCEPTED -> {
                    // Task 15 / B2: ANSWER_ACK already went out immediately (optimistic, hot
                    // path, unchanged) -- this is the SEPARATE, later signal that tells a client
                    // which sequences are now actually safe to discard from its RingBuffer.
                    // ActorRef.tell() is thread-safe by design, so calling it from this callback
                    // (not the actor's own thread) is fine -- unlike reaching back into `state`.
                    broadcastTarget.tell(RoomState.buildCommittedSeq(roomId, committedSequenceByStudent));
                }
                case FENCED -> {
                    // Zombie-actor fix: another pod's epoch is ahead of this one's -- a
                    // permanent, definitive loss of ownership (§5.8), never a transient
                    // condition. `self` is safe to `.tell()` from this foreign thread even
                    // though `getContext()` itself would not be.
                    self.tell(LeaseLost.INSTANCE);
                }
                case DISABLED -> {
                    // Phase 1 default (NoopRoomSnapshotStore, room store off) -- expected on every
                    // flush, not a failure of any kind. No action, no log: logging this at any
                    // level above trace would spam production for entirely by-design behavior.
                }
            }
        });
    }
}
