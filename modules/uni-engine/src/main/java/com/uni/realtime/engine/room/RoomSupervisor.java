package com.uni.realtime.engine.room;

import com.uni.realtime.engine.definition.TickMode;
import com.uni.realtime.engine.metrics.EngineMetrics;
import com.uni.realtime.engine.net.ChannelReplyActor;
import com.uni.realtime.engine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.TeacherCommand;
import io.netty.channel.Channel;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Task 13: the missing link between the wire and {@code RoomActor} that Task 2's note deferred
 * ("Join room / student_index / broadcast / tick coalescing — thuộc Task 3, 8, 11") and Task 9's
 * note deferred again ("nối toàn chuỗi thật... thuộc Task 13"). One instance per Engine pod.
 *
 * <p>Owns three things a bare {@code RoomActor} deliberately knows nothing about (Task 3 kept it
 * transport-agnostic on purpose):
 * <ul>
 *   <li>Which {@code RoomActor} exists for a given {@code room_id} -- spawned lazily on the
 *       first {@code JOIN_ROOM} for it, since Phase 1 has no separate "create a room" flow.</li>
 *   <li>One {@link ChannelReplyActor} per accepted internal-frame-channel connection, memoized,
 *       so a room's {@code replyTo}/{@code broadcastTarget} can actually reach a Gateway pod.</li>
 *   <li>Which connections are subscribed to a room's broadcasts -- learned from {@code JOIN_ROOM}
 *       (decision B1: Engine sends one copy per Gateway pod, never one per student).</li>
 * </ul>
 */
public final class RoomSupervisor extends AbstractBehavior<RoomSupervisor.Command> {

    private static final Logger log = LoggerFactory.getLogger(RoomSupervisor.class);

    public sealed interface Command {}

    /** A decoded, owned {@link GameMessage} that arrived on {@code sourceChannel} (Task 10 already filtered ownership). */
    public record Dispatch(GameMessage message, Channel sourceChannel) implements Command {}

    /** The internal-frame-channel connection to a Gateway pod died -- stop treating it as a broadcast subscriber. */
    public record ChannelClosed(Channel channel) implements Command {}

    /**
     * Test/ops-only introspection hook: there is no wire message anywhere in Phase 1's schema
     * for "start this question with this content" (no game-definition-authoring format has been
     * decided -- tech-design.md Task 11 note). Real question content can only be driven by
     * talking to the {@code RoomActor} directly, so this is how a walking-skeleton test does
     * that without inventing a protocol decision that isn't this task's to make.
     */
    public record GetRoomActor(String roomId, ActorRef<ActorRef<RoomActor.Command>> replyTo) implements Command {}

    private record RoomBroadcast(GameMessage message) implements Command {}

    /** A room's {@code RoomActor} stopped (e.g. {@code EndGame}) -- stop treating it as live. */
    private record RoomTerminated(String roomId) implements Command {}

    /** A connection's {@link ChannelReplyActor} stopped, whether by {@link ChannelClosed} or an unexpected crash. */
    private record ReplyActorTerminated(Channel channel) implements Command {}

    public static Behavior<Command> create(
            RoomOwnership roomOwnership, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, Clock clock) {
        return Behaviors.setup(context ->
                new RoomSupervisor(context, roomOwnership, scoreCalculator, engineMetrics, clock));
    }

    private final RoomOwnership roomOwnership;
    private final ScoreCalculator scoreCalculator;
    private final EngineMetrics engineMetrics;
    private final Clock clock;

    // Plain HashMap/LinkedHashSet, not concurrent collections: RoomSupervisor is a single actor
    // (AbstractBehavior) and the actor model guarantees only its own dispatcher thread ever
    // touches this state -- ConcurrentHashMap here would just be overhead documenting a sharing
    // pattern that does not exist.
    private final Map<String, ActorRef<RoomActor.Command>> roomsByRoomId = new HashMap<>();
    private final Map<Channel, ActorRef<GameMessage>> replyActorsByChannel = new HashMap<>();
    private final Map<String, Set<ActorRef<GameMessage>>> subscribersByRoom = new HashMap<>();

    private RoomSupervisor(ActorContext<Command> context, RoomOwnership roomOwnership,
            ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, Clock clock) {
        super(context);
        this.roomOwnership = roomOwnership;
        this.scoreCalculator = scoreCalculator;
        this.engineMetrics = engineMetrics;
        this.clock = clock;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Dispatch.class, this::onDispatch)
                .onMessage(ChannelClosed.class, this::onChannelClosed)
                .onMessage(GetRoomActor.class, this::onGetRoomActor)
                .onMessage(RoomBroadcast.class, this::onRoomBroadcast)
                .onMessage(RoomTerminated.class, this::onRoomTerminated)
                .onMessage(ReplyActorTerminated.class, this::onReplyActorTerminated)
                .build();
    }

    private Behavior<Command> onDispatch(Dispatch command) {
        GameMessage message = command.message();
        String roomId = message.getRoomId();

        switch (message.getPayloadCase()) {
            case JOIN_ROOM -> {
                ActorRef<RoomActor.Command> room = roomsByRoomId.computeIfAbsent(roomId, this::spawnRoom);
                ActorRef<GameMessage> replyTo = replyActorFor(command.sourceChannel(), roomId);
                subscribersByRoom.computeIfAbsent(roomId, unused -> new LinkedHashSet<>()).add(replyTo);
                room.tell(new RoomActor.JoinRoom(message.getStudentId(), message.getJoinRoom().getDisplayName(), replyTo));
                engineMetrics.recordMessageEnqueued();
            }
            case SUBMIT_ANSWER -> {
                ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
                if (room == null) {
                    log.warn("dropping SUBMIT_ANSWER for room {}: no one has joined it on this pod yet", roomId);
                    return this;
                }
                ActorRef<GameMessage> replyTo = replyActorFor(command.sourceChannel(), roomId);
                room.tell(new RoomActor.SubmitAnswer(message.getStudentId(), message.getSequence(),
                        message.getSubmitAnswer().getQuestionId(), message.getSubmitAnswer().getAnswerIdsList(),
                        message.getClientTimestampMs(), replyTo));
                engineMetrics.recordMessageEnqueued();
            }
            case TEACHER_COMMAND -> dispatchTeacherCommand(roomId, message.getTeacherCommand());
            default -> log.warn("dropping {} for room {}: no dispatch wired for this payload yet",
                    message.getPayloadCase(), roomId);
        }
        return this;
    }

    private void dispatchTeacherCommand(String roomId, TeacherCommand teacherCommand) {
        ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
        if (room == null) {
            log.warn("dropping TEACHER_COMMAND for room {}: no one has joined it on this pod yet", roomId);
            return;
        }
        switch (teacherCommand.getCommand()) {
            case START_GAME -> room.tell(new RoomActor.StartGame());
            case END_GAME -> room.tell(new RoomActor.EndGame());
            // NEXT_STEP needs question content (prompt/choices) that no decided format supplies
            // yet (Task 11 note); PAUSE has no corresponding RoomActor phase; KICK_STUDENT needs
            // a student_id -> channel lookup this pod does not have. Left unwired on purpose.
            default -> log.warn("TeacherCommand.{} not wired for room {} yet", teacherCommand.getCommand(), roomId);
        }
    }

    private ActorRef<RoomActor.Command> spawnRoom(String roomId) {
        ActorRef<GameMessage> broadcastTarget =
                getContext().messageAdapter(GameMessage.class, RoomBroadcast::new);
        ActorRef<RoomActor.Command> room = getContext().spawn(
                RoomActor.create(roomId, clock, scoreCalculator, engineMetrics, TickMode.COALESCE, broadcastTarget),
                "room-" + roomId);
        // Without this, a RoomActor that stops (EndGame) leaves a dead ActorRef behind in
        // roomsByRoomId forever -- a later JOIN_ROOM for the same room_id would find it via
        // computeIfAbsent and dead-letter into it instead of spawning a fresh room.
        getContext().watchWith(room, new RoomTerminated(roomId));
        return room;
    }

    private ActorRef<GameMessage> replyActorFor(Channel channel, String roomId) {
        // spawnAnonymous, not a name derived from the Channel: EmbeddedChannel (every test in
        // this codebase that doesn't open a real socket) hands out the same fixed id for every
        // instance, which would collide the moment a test used two of them.
        return replyActorsByChannel.computeIfAbsent(channel, ch -> {
            ActorRef<GameMessage> replyActor =
                    getContext().spawnAnonymous(ChannelReplyActor.create(ch, roomOwnership.ownerPodId(roomId)));
            // Safety net alongside the explicit ChannelClosed message: if this actor ever stops
            // on its own (an unhandled exception, however unlikely today), it must not stay a
            // phantom subscriber that broadcasts silently vanish into.
            getContext().watchWith(replyActor, new ReplyActorTerminated(ch));
            return replyActor;
        });
    }

    private Behavior<Command> onChannelClosed(ChannelClosed command) {
        ActorRef<GameMessage> replyActor = replyActorsByChannel.get(command.channel());
        forgetConnection(command.channel());
        if (replyActor != null) {
            getContext().stop(replyActor);
        }
        return this;
    }

    private Behavior<Command> onReplyActorTerminated(ReplyActorTerminated command) {
        forgetConnection(command.channel());
        return this;
    }

    private void forgetConnection(Channel channel) {
        ActorRef<GameMessage> replyActor = replyActorsByChannel.remove(channel);
        if (replyActor != null) {
            subscribersByRoom.values().forEach(subscribers -> subscribers.remove(replyActor));
        }
    }

    private Behavior<Command> onGetRoomActor(GetRoomActor command) {
        command.replyTo().tell(roomsByRoomId.computeIfAbsent(command.roomId(), this::spawnRoom));
        return this;
    }

    private Behavior<Command> onRoomBroadcast(RoomBroadcast command) {
        GameMessage message = command.message();
        Set<ActorRef<GameMessage>> subscribers = subscribersByRoom.getOrDefault(message.getRoomId(), Set.of());
        subscribers.forEach(subscriber -> subscriber.tell(message));
        return this;
    }

    private Behavior<Command> onRoomTerminated(RoomTerminated command) {
        roomsByRoomId.remove(command.roomId());
        subscribersByRoom.remove(command.roomId());
        return this;
    }
}
