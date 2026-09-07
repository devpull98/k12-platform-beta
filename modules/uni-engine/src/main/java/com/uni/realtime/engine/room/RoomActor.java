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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
        if (tickMode != TickMode.COALESCE) {
            throw new IllegalArgumentException(
                    "RoomActor only implements TickMode.COALESCE in Phase 1, got " + tickMode);
        }
        return Behaviors.withTimers(timers -> Behaviors.setup(
                context -> new RoomActor(context, timers, roomId, clock, scoreCalculator, engineMetrics, broadcastTarget)));
    }

    private final String roomId;
    private final Clock clock;
    private final RoomState state;
    private final Timer processingTimer;
    private final EngineMetrics engineMetrics;
    private final TimerScheduler<Command> timers;
    private final ActorRef<GameMessage> broadcastTarget;

    private boolean flushScheduled = false;
    private long lastFlushAtMs = 0;

    private RoomActor(ActorContext<Command> context, TimerScheduler<Command> timers, String roomId, Clock clock,
            ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, ActorRef<GameMessage> broadcastTarget) {
        super(context);
        this.timers = timers;
        this.roomId = roomId;
        this.clock = clock;
        this.state = new RoomState(roomId, clock, scoreCalculator);
        this.processingTimer = engineMetrics.processingLatencyTimer();
        this.engineMetrics = engineMetrics;
        this.broadcastTarget = broadcastTarget;
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
        command.replyTo().tell(fullSnapshot);
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
    }
}
