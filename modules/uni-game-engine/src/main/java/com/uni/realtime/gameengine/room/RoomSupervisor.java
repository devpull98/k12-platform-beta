package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.TickMode;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.events.GameEventPublisher;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.net.ChannelReplyActor;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.TeacherCommand;
import com.uni.realtime.protocol.TeamAssignment;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * P2 Task 26: same test/ops-only spirit as {@link GetRoomActor} above -- spawns a room
     * pre-configured with cooperative/team config BEFORE any real {@code JOIN_ROOM} arrives for
     * it, so a real WebSocket-driven E2E test can exercise {@code GAME_MODE_COOPERATIVE}/{@code
     * TEAM} through the actual join path. There is still no decided game-definition-authoring
     * wire format (Task 11) for a real client/CMS to configure a room this way in production --
     * this hook exists purely because there is no other way to get such a room to exist for a
     * real client to join yet, exactly the reason {@link GetRoomActor} bypasses question content
     * the same way. No-op (returns the existing actor) if {@code roomId} was already spawned.
     */
    public record SpawnConfiguredRoom(String roomId, GameMode gameMode, int progressTarget,
            List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition,
            ActorRef<ActorRef<RoomActor.Command>> replyTo) implements Command {}

    private record RoomBroadcast(GameMessage message) implements Command {}

    /** A room's {@code RoomActor} stopped (e.g. {@code EndGame}) -- stop treating it as live. */
    private record RoomTerminated(String roomId) implements Command {}

    /** A connection's {@link ChannelReplyActor} stopped, whether by {@link ChannelClosed} or an unexpected crash. */
    private record ReplyActorTerminated(Channel channel) implements Command {}

    /**
     * Task 14: {@code snapshotStore.load(roomId)} piped back to self (never awaited inline --
     * this actor's own thread must never block on the room store any more than {@code RoomActor}'s may,
     * §13.2). {@code snapshotBytes} is {@code null} both for "nothing stored" and for "the load
     * failed" -- {@link RoomState#restore} is only ever worth calling with real bytes, and a
     * failed load is exactly as safe to treat as a brand-new room as a genuinely empty one.
     */
    private record SnapshotLoaded(String roomId, byte[] snapshotBytes) implements Command {}

    private record PendingJoin(GameMessage message, Channel sourceChannel) {}

    public static Behavior<Command> create(
            RoomOwnership roomOwnership, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, Clock clock) {
        return create(roomOwnership, scoreCalculator, engineMetrics, clock, NoopRoomSnapshotStore.INSTANCE);
    }

    /**
     * Task 14 (Hot Snapshot): the full-featured constructor, kept separate from the four-arg
     * {@link #create} above for the same reason {@code RoomActor} added an overload rather than
     * changing its signature -- every existing caller with no snapshot store to offer
     * ({@code RoomSupervisorTest}, {@code WalkingSkeletonTest}) keeps compiling unchanged.
     */
    public static Behavior<Command> create(RoomOwnership roomOwnership, ScoreCalculator scoreCalculator,
            EngineMetrics engineMetrics, Clock clock, RoomSnapshotStore snapshotStore) {
        return create(roomOwnership, scoreCalculator, engineMetrics, clock, snapshotStore, null);
    }

    /**
     * Task 18: adds {@code gameEventPublisher} on top of Task 14's five-arg overload above, same
     * additive shape -- every existing caller with no publisher to offer keeps compiling
     * unchanged.
     *
     * @param gameEventPublisher forwarded to every {@code RoomActor} this supervisor spawns, or
     *     {@code null} to skip event publishing entirely (Phase 1 default, before wiring).
     */
    public static Behavior<Command> create(RoomOwnership roomOwnership, ScoreCalculator scoreCalculator,
            EngineMetrics engineMetrics, Clock clock, RoomSnapshotStore snapshotStore,
            GameEventPublisher gameEventPublisher) {
        return Behaviors.setup(context -> new RoomSupervisor(
                context, roomOwnership, scoreCalculator, engineMetrics, clock, snapshotStore, gameEventPublisher));
    }

    private final RoomOwnership roomOwnership;
    private final ScoreCalculator scoreCalculator;
    private final EngineMetrics engineMetrics;
    private final Clock clock;
    private final RoomSnapshotStore snapshotStore;
    private final GameEventPublisher gameEventPublisher;

    // Plain HashMap/LinkedHashSet, not concurrent collections: RoomSupervisor is a single actor
    // (AbstractBehavior) and the actor model guarantees only its own dispatcher thread ever
    // touches this state -- ConcurrentHashMap here would just be overhead documenting a sharing
    // pattern that does not exist.
    private final Map<String, ActorRef<RoomActor.Command>> roomsByRoomId = new HashMap<>();
    private final Map<Channel, ActorRef<GameMessage>> replyActorsByChannel = new HashMap<>();
    private final Map<String, Set<ActorRef<GameMessage>>> subscribersByRoom = new HashMap<>();
    /** room_id -> joins received while that room's snapshot load is still in flight (Task 14). */
    private final Map<String, List<PendingJoin>> pendingJoinsByRoom = new HashMap<>();

    private RoomSupervisor(ActorContext<Command> context, RoomOwnership roomOwnership,
            ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, Clock clock, RoomSnapshotStore snapshotStore,
            GameEventPublisher gameEventPublisher) {
        super(context);
        this.roomOwnership = roomOwnership;
        this.scoreCalculator = scoreCalculator;
        this.engineMetrics = engineMetrics;
        this.clock = clock;
        this.snapshotStore = snapshotStore;
        this.gameEventPublisher = gameEventPublisher;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Dispatch.class, this::onDispatch)
                .onMessage(ChannelClosed.class, this::onChannelClosed)
                .onMessage(GetRoomActor.class, this::onGetRoomActor)
                .onMessage(SpawnConfiguredRoom.class, this::onSpawnConfiguredRoom)
                .onMessage(RoomBroadcast.class, this::onRoomBroadcast)
                .onMessage(RoomTerminated.class, this::onRoomTerminated)
                .onMessage(ReplyActorTerminated.class, this::onReplyActorTerminated)
                .onMessage(SnapshotLoaded.class, this::onSnapshotLoaded)
                .build();
    }

    private Behavior<Command> onDispatch(Dispatch command) {
        GameMessage message = command.message();
        String roomId = message.getRoomId();

        switch (message.getPayloadCase()) {
            case JOIN_ROOM -> handleJoin(roomId, message, command.sourceChannel());
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
            case RESYNC -> handleResync(roomId, message, command.sourceChannel());
            case TEACHER_COMMAND -> dispatchTeacherCommand(roomId, message.getTeacherCommand());
            case DRAFT_UPDATE -> {
                ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
                if (room == null) {
                    log.warn("dropping UPDATE_DRAFT for room {}: no one has joined it on this pod yet", roomId);
                } else {
                    room.tell(new RoomActor.UpdateDraft(
                            message.getStudentId(), message.getDraftUpdate().getDraftContent()));
                }
            }
            case PAYLOAD_NOT_SET -> dispatchNoPayloadMessage(roomId, message);
            default -> log.warn("dropping {} for room {}: no dispatch wired for this payload yet",
                    message.getPayloadCase(), roomId);
        }
        return this;
    }

    /**
     * Leave-room flow: {@code STUDENT_LEFT} carries no oneof payload (same shape as
     * {@code HEARTBEAT}/{@code UPDATE_DRAFT}), so it never reaches {@code onDispatch}'s
     * payload-based switch above -- it is distinguished by {@code MessageType} instead.
     */
    private void dispatchNoPayloadMessage(String roomId, GameMessage message) {
        switch (message.getType()) {
            case STUDENT_LEFT -> {
                ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
                if (room != null) {
                    room.tell(new RoomActor.StudentDisconnected(message.getStudentId()));
                }
                // room == null: never spawned on this pod (or already terminated) -- nothing to update.
            }
            default -> log.warn("dropping {} (type {}) for room {}: no dispatch wired for this payload yet",
                    message.getPayloadCase(), message.getType(), roomId);
        }
    }

    /**
     * PH-3 / §9.3: assumes the channel already joined this room via a prior {@code JOIN_ROOM} on
     * this same reconnect ({@code JoinTokenAuthHandler} requires JOIN_ROOM as the first frame of
     * every new connection), so {@code replyActorFor} below finds/reuses the same subscriber
     * {@code deliverJoin} already registered -- no extra subscription bookkeeping needed, same
     * as the {@code SUBMIT_ANSWER} branch above.
     */
    private void handleResync(String roomId, GameMessage message, Channel sourceChannel) {
        ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
        if (room == null) {
            log.warn("dropping RESYNC for room {}: no one has joined it on this pod yet", roomId);
            return;
        }
        ActorRef<GameMessage> replyTo = replyActorFor(sourceChannel, roomId);
        room.tell(new RoomActor.Resync(message.getStudentId(), message.getResync().getLastAckedSeq(),
                message.getResync().getPendingList(), replyTo));
        engineMetrics.recordMessageEnqueued();
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
            case KICK_STUDENT -> room.tell(new RoomActor.KickStudent(teacherCommand.getTargetStudentId()));
            // NEXT_STEP needs question content (prompt/choices) that no decided format supplies
            // yet (Task 11 note). PAUSE has no corresponding RoomActor phase AND no RESUME command
            // exists anywhere in the schema -- there is no defined semantics (does a paused
            // question's deadline freeze? does scoring pause?) to implement without inventing
            // gameplay rules this task is not scoped to invent. Left unwired on purpose.
            default -> log.warn("TeacherCommand.{} not wired for room {} yet", teacherCommand.getCommand(), roomId);
        }
    }

    /**
     * Task 14: a join for a room this pod has not spawned yet is buffered rather than dropped
     * (unlike {@code SUBMIT_ANSWER}, there is no established client-retry contract for a lost
     * {@code JOIN_ROOM} -- PH-3 status unresolved). The very first join for a given room_id
     * triggers an async {@link RoomSnapshotStore#load}; every join that arrives while that load
     * is still in flight just joins the queue for the same room instead of starting a second one.
     */
    private void handleJoin(String roomId, GameMessage message, Channel sourceChannel) {
        ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
        if (room != null) {
            deliverJoin(room, message, sourceChannel);
            return;
        }
        List<PendingJoin> pending = pendingJoinsByRoom.computeIfAbsent(roomId, unused -> new ArrayList<>());
        pending.add(new PendingJoin(message, sourceChannel));
        if (pending.size() > 1) {
            // Prove-it (2026-09-07): removing this guard turned exactly this scenario's test red
            // (1 expected load call, got 2) -- confirms the test actually exercises the dedupe.
            return; // a load for this room is already in flight -- this join just queued behind it
        }
        getContext().pipeToSelf(snapshotStore.load(roomId), (loaded, failure) ->
                new SnapshotLoaded(roomId, failure != null ? null : loaded.orElse(null)));
    }

    /**
     * Real-infra chaos test finding (docker-compose.dev.yml, kill+recover a room's owning pod):
     * {@link RoomSnapshotStore#load} returns the RAW envelope written by {@link RoomActor}'s
     * {@code maybeSnapshot} ({@code SnapshotEnvelope.wrap(...)} -- schema_version/epoch/crc32
     * header, then the actual {@code RoomState.serializeSnapshot()} payload). Nothing on this
     * path ever called {@link SnapshotEnvelope#unwrap} before handing bytes to
     * {@code RoomActor.create}'s {@code restoreFromSnapshot}, which expects the INNER payload,
     * not the envelope -- every real restore threw {@code ActorInitializationException} (a
     * garbled {@code GamePhase} enum name from misaligned fields), invisible to every existing
     * test because {@code RoomActorSnapshotTest} feeds {@code RoomState.serializeSnapshot()}
     * output directly, bypassing the envelope entirely. Fixed here, the one place that actually
     * hands loaded bytes onward. A corrupt/schema-mismatched envelope (§5.8) still degrades to
     * "spawn empty", exactly like a missing snapshot -- {@link SnapshotEnvelope#unwrap} already
     * returns {@code Optional.empty()} for both, never throws.
     */
    private Behavior<Command> onSnapshotLoaded(SnapshotLoaded command) {
        byte[] payload = command.snapshotBytes() == null ? null
                : SnapshotEnvelope.unwrap(command.snapshotBytes()).map(SnapshotEnvelope.Unwrapped::payload).orElse(null);
        ActorRef<RoomActor.Command> room = spawnRoom(command.roomId(), payload);
        List<PendingJoin> pending = pendingJoinsByRoom.remove(command.roomId());
        if (pending != null) {
            pending.forEach(join -> deliverJoin(room, join.message(), join.sourceChannel()));
        }
        return this;
    }

    private void deliverJoin(ActorRef<RoomActor.Command> room, GameMessage message, Channel sourceChannel) {
        String roomId = message.getRoomId();
        ActorRef<GameMessage> replyTo = replyActorFor(sourceChannel, roomId);
        subscribersByRoom.computeIfAbsent(roomId, unused -> new LinkedHashSet<>()).add(replyTo);
        room.tell(new RoomActor.JoinRoom(message.getStudentId(), message.getJoinRoom().getDisplayName(), replyTo));
        engineMetrics.recordMessageEnqueued();
    }

    /**
     * @param snapshotBytes bytes from a prior {@link RoomSnapshotStore#load}, or {@code null} to
     *     spawn empty -- see {@link #onGetRoomActor}, the one caller that intentionally skips the
     *     load (test/ops hook, not part of the real join path).
     */
    private ActorRef<RoomActor.Command> spawnRoom(String roomId, byte[] snapshotBytes) {
        return spawnRoom(roomId, snapshotBytes, GameMode.GAME_MODE_SOLO, 0, List.of(),
                SharedResourceType.NONE, 0, List.of(), ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED);
    }

    /** P2 Task 26: additive over the two-arg {@link #spawnRoom} above -- see {@link SpawnConfiguredRoom}'s javadoc. */
    private ActorRef<RoomActor.Command> spawnRoom(String roomId, byte[] snapshotBytes, GameMode gameMode,
            int progressTarget, List<ProgressStage> progressStages, SharedResourceType sharedResourceType,
            int sharedResourcePenalty, List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation,
            WinCondition winCondition) {
        ActorRef<GameMessage> broadcastTarget =
                getContext().messageAdapter(GameMessage.class, RoomBroadcast::new);
        long epoch = roomOwnership.epochOf(roomId);
        ActorRef<RoomActor.Command> room = getContext().spawn(
                RoomActor.create(roomId, clock, scoreCalculator, engineMetrics, TickMode.COALESCE, broadcastTarget,
                        snapshotStore, epoch, snapshotBytes, MissedStepPolicy.ZERO, gameEventPublisher,
                        gameMode, progressTarget, progressStages, sharedResourceType, sharedResourcePenalty,
                        teamRosters, scoreAggregation, winCondition),
                "room-" + roomId);
        // Without this, a RoomActor that stops (EndGame) leaves a dead ActorRef behind in
        // roomsByRoomId forever -- a later JOIN_ROOM for the same room_id would find it via
        // computeIfAbsent and dead-letter into it instead of spawning a fresh room.
        getContext().watchWith(room, new RoomTerminated(roomId));
        roomsByRoomId.put(roomId, room);
        return room;
    }

    private Behavior<Command> onSpawnConfiguredRoom(SpawnConfiguredRoom command) {
        ActorRef<RoomActor.Command> existing = roomsByRoomId.get(command.roomId());
        ActorRef<RoomActor.Command> room = existing != null ? existing
                : spawnRoom(command.roomId(), null, command.gameMode(), command.progressTarget(),
                        command.progressStages(), command.sharedResourceType(), command.sharedResourcePenalty(),
                        command.teamRosters(), command.scoreAggregation(), command.winCondition());
        command.replyTo().tell(room);
        return this;
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
        // Test/ops-only hook (see the class javadoc): spawns synchronously with no snapshot load,
        // unlike the real join path in handleJoin/onSnapshotLoaded. Callers of this hook
        // (WalkingSkeletonTest, RoomSupervisorTest) need a room to exist right now, not Task 14
        // recovery behavior -- that is covered separately by RoomActorSnapshotTest.
        ActorRef<RoomActor.Command> room = roomsByRoomId.get(command.roomId());
        command.replyTo().tell(room != null ? room : spawnRoom(command.roomId(), null));
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
