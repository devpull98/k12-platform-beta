package com.uni.realtime.engine.room;

import com.uni.realtime.engine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.japi.function.Function;

import java.time.Clock;
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

    public sealed interface Command {}

    public record StartGame() implements Command {}

    public record StartQuestion(String questionId, long durationMs) implements Command {}

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

    public static Behavior<Command> create(
            String roomId, Clock clock, ScoreCalculator scoreCalculator, MeterRegistry meterRegistry) {
        return Behaviors.setup(context -> new RoomActor(context, roomId, clock, scoreCalculator, meterRegistry));
    }

    private final String roomId;
    private final RoomState state;
    private final Timer processingTimer;

    private RoomActor(ActorContext<Command> context, String roomId, Clock clock,
            ScoreCalculator scoreCalculator, MeterRegistry meterRegistry) {
        super(context);
        this.roomId = roomId;
        this.state = new RoomState(roomId, clock, scoreCalculator);
        this.processingTimer = Timer.builder("engine.room.actor.processing.time")
                .tag("room_id", roomId)
                .register(meterRegistry);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartGame.class, watched(this::onStartGame))
                .onMessage(StartQuestion.class, watched(this::onStartQuestion))
                .onMessage(SubmitAnswer.class, watched(this::onSubmitAnswer))
                .onMessage(EndGame.class, watched(this::onEndGame))
                .build();
    }

    private <C extends Command> Function<C, Behavior<Command>> watched(Function<C, Behavior<Command>> handler) {
        return command -> {
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
        state.startQuestion(command.questionId(), command.durationMs());
        return this;
    }

    private Behavior<Command> onSubmitAnswer(SubmitAnswer command) {
        GameMessage ack = state.submitAnswer(command.studentId(), command.sequence(),
                command.questionId(), command.answerIds());
        command.replyTo().tell(ack);
        return this;
    }

    private Behavior<Command> onEndGame(EndGame command) {
        state.endGame();
        return Behaviors.stopped();
    }
}
