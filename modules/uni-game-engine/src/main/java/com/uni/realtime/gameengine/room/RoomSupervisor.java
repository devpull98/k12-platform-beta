package com.uni.realtime.gameengine.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uni.realtime.gameengine.definition.DefinitionLoader;
import com.uni.realtime.gameengine.definition.DefinitionRejectedException;
import com.uni.realtime.gameengine.definition.GameDefinition;
import com.uni.realtime.gameengine.definition.GameSessionDefinitionMapper;
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
import com.uni.realtime.protocol.*;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class RoomSupervisor extends AbstractBehavior<RoomSupervisor.Command> {

    private static final Logger log = LoggerFactory.getLogger(RoomSupervisor.class);

    public sealed interface Command {}

    /** A decoded, owned {@link GameMessage} that arrived on {@code sourceChannel} (Task 10 already filtered ownership). */
    public record Dispatch(GameMessage message, Channel sourceChannel) implements Command {}

    /** The internal-frame-channel connection to a Gateway pod died -- stop treating it as a broadcast subscriber. */
    public record ChannelClosed(Channel channel) implements Command {}

    public record GetRoomActor(String roomId, ActorRef<ActorRef<RoomActor.Command>> replyTo) implements Command {}

    public record SpawnConfiguredRoom(String roomId, GameMode gameMode, int progressTarget,
            List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition,
            ActorRef<ActorRef<RoomActor.Command>> replyTo) implements Command {}

    private record RoomBroadcast(GameMessage message) implements Command {}

    /** A room's {@code RoomActor} stopped (e.g. {@code EndGame}) -- stop treating it as live. */
    private record RoomTerminated(String roomId) implements Command {}

    /** A connection's {@link ChannelReplyActor} stopped, whether by {@link ChannelClosed} or an unexpected crash. */
    private record ReplyActorTerminated(Channel channel) implements Command {}

    private record SnapshotLoaded(String roomId, byte[] snapshotBytes, byte[] definitionJsonBytes) implements Command {}

    private record PendingJoin(GameMessage message, Channel sourceChannel) {}

    public static Behavior<Command> create(
            RoomOwnership roomOwnership, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, Clock clock) {
        return create(roomOwnership, scoreCalculator, engineMetrics, clock, NoopRoomSnapshotStore.INSTANCE);
    }

    public static Behavior<Command> create(RoomOwnership roomOwnership, ScoreCalculator scoreCalculator,
            EngineMetrics engineMetrics, Clock clock, RoomSnapshotStore snapshotStore) {
        return create(roomOwnership, scoreCalculator, engineMetrics, clock, snapshotStore, null);
    }

    public static Behavior<Command> create(RoomOwnership roomOwnership, ScoreCalculator scoreCalculator,
            EngineMetrics engineMetrics, Clock clock, RoomSnapshotStore snapshotStore,
            GameEventPublisher gameEventPublisher) {
        return create(roomOwnership, scoreCalculator, engineMetrics, clock, snapshotStore, gameEventPublisher,
                NoopGameSessionDefinitionStore.INSTANCE);
    }

    /**
     * Closes "gap Task 11" for the real join path: {@code definitionStore} is checked (async,
     * alongside the Hot Snapshot load already done for crash recovery) the first time a room_id
     * needs spawning, so a CMS-provisioned {@code GameDefinition} is actually used instead of
     * every real room silently defaulting to SOLO. See plan.md Task 11/28 and
     * docs/specs/tech-design/cms-game-session-provisioning.md.
     */
    public static Behavior<Command> create(RoomOwnership roomOwnership, ScoreCalculator scoreCalculator,
            EngineMetrics engineMetrics, Clock clock, RoomSnapshotStore snapshotStore,
            GameEventPublisher gameEventPublisher, GameSessionDefinitionStore definitionStore) {
        return Behaviors.setup(context -> new RoomSupervisor(context, roomOwnership, scoreCalculator, engineMetrics,
                clock, snapshotStore, gameEventPublisher,
                definitionStore != null ? definitionStore : NoopGameSessionDefinitionStore.INSTANCE));
    }

    private final RoomOwnership roomOwnership;
    private final ScoreCalculator scoreCalculator;
    private final EngineMetrics engineMetrics;
    private final Clock clock;
    private final RoomSnapshotStore snapshotStore;
    private final GameEventPublisher gameEventPublisher;
    private final GameSessionDefinitionStore definitionStore;
    // Plain new ObjectMapper() deliberately, not Spring's configured bean -- RoomSupervisor stays
    // framework-agnostic (plain Pekko, JUnit-testable without a Spring context), same reasoning as
    // definitionLoader below. Default Jackson behavior is what GameSessionDefinitionRequest's
    // record fields need; no custom (de)serializers are registered anywhere for this contract.
    private final GameSessionDefinitionMapper definitionMapper =
            new GameSessionDefinitionMapper(new ObjectMapper(), new DefinitionLoader());

    private final Map<String, ActorRef<RoomActor.Command>> roomsByRoomId = new HashMap<>();
    private final Map<Channel, ActorRef<GameMessage>> replyActorsByChannel = new HashMap<>();
    private final Map<String, Set<ActorRef<GameMessage>>> subscribersByRoom = new HashMap<>();
    private final Map<String, List<PendingJoin>> pendingJoinsByRoom = new HashMap<>();

    private RoomSupervisor(ActorContext<Command> context, RoomOwnership roomOwnership,
            ScoreCalculator scoreCalculator, EngineMetrics engineMetrics, Clock clock, RoomSnapshotStore snapshotStore,
            GameEventPublisher gameEventPublisher, GameSessionDefinitionStore definitionStore) {
        super(context);
        this.roomOwnership = roomOwnership;
        this.scoreCalculator = scoreCalculator;
        this.engineMetrics = engineMetrics;
        this.clock = clock;
        this.snapshotStore = snapshotStore;
        this.gameEventPublisher = gameEventPublisher;
        this.definitionStore = definitionStore;
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


    private void dispatchNoPayloadMessage(String roomId, GameMessage message) {
        if (message.getType() == MessageType.STUDENT_LEFT) {
            ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
            if (room != null) {
                room.tell(new RoomActor.StudentDisconnected(message.getStudentId()));
            }
        } else {
            log.warn("dropping {} (type {}) for room {}: no dispatch wired for this payload yet",
                    message.getPayloadCase(), message.getType(), roomId);
        }
    }

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
            default -> log.warn("TeacherCommand.{} not wired for room {} yet", teacherCommand.getCommand(), roomId);
        }
    }

    private void handleJoin(String roomId, GameMessage message, Channel sourceChannel) {
        ActorRef<RoomActor.Command> room = roomsByRoomId.get(roomId);
        if (room != null) {
            deliverJoin(room, message, sourceChannel);
            return;
        }
        List<PendingJoin> pending = pendingJoinsByRoom.computeIfAbsent(roomId, unused -> new ArrayList<>());
        pending.add(new PendingJoin(message, sourceChannel));
        if (pending.size() > 1) {
            return;
        }
        // Loaded together (both async, off this actor's thread): the Hot Snapshot (crash
        // recovery) and any GameDefinition a CMS provisioned ahead of time for this room_id
        // (Task 11/28 -- the ONLY way a real join ever learns what game this room should run).
        // A failure in either must not sacrifice the other's result, so each is defanged on its
        // own before combining.
        CompletableFuture<Optional<byte[]>> snapshotFuture =
                snapshotStore.load(roomId).exceptionally(failure -> Optional.empty());
        CompletableFuture<Optional<byte[]>> definitionFuture =
                definitionStore.load(roomId).exceptionally(failure -> Optional.empty());
        CompletableFuture<SnapshotLoaded> combined = snapshotFuture.thenCombine(definitionFuture,
                (snapshotBytes, definitionBytes) ->
                        new SnapshotLoaded(roomId, snapshotBytes.orElse(null), definitionBytes.orElse(null)));
        getContext().pipeToSelf(combined,
                (loaded, failure) -> failure != null ? new SnapshotLoaded(roomId, null, null) : loaded);
    }

    private Behavior<Command> onSnapshotLoaded(SnapshotLoaded command) {
        byte[] payload = command.snapshotBytes() == null ? null
                : SnapshotEnvelope.unwrap(command.snapshotBytes()).map(SnapshotEnvelope.Unwrapped::payload).orElse(null);
        GameDefinition gameDefinition = resolveProvisionedDefinition(command.roomId(), command.definitionJsonBytes());
        ActorRef<RoomActor.Command> room = spawnRoom(command.roomId(), payload, gameDefinition);
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

    private ActorRef<RoomActor.Command> spawnRoom(String roomId, byte[] snapshotBytes) {
        return spawnRoom(roomId, snapshotBytes, GameDefinition.defaultSoloDefinition());
    }

    /**
     * The real join path (via {@link #onSnapshotLoaded}) always goes through here now, with
     * whatever {@link GameDefinition} {@link #resolveProvisionedDefinition} resolved -- either a
     * CMS-provisioned one, or the same default SOLO definition this always spawned before Task 11
     * had a real provisioning source.
     */
    private ActorRef<RoomActor.Command> spawnRoom(String roomId, byte[] snapshotBytes, GameDefinition gameDefinition) {
        ActorRef<GameMessage> broadcastTarget =
                getContext().messageAdapter(GameMessage.class, RoomBroadcast::new);
        long epoch = roomOwnership.epochOf(roomId);
        RoomDependencies deps = RoomDependencies.of(
                clock, scoreCalculator, engineMetrics, broadcastTarget, snapshotStore, gameEventPublisher);
        ActorRef<RoomActor.Command> room = getContext().spawn(
                RoomActor.create(roomId, deps, gameDefinition, TickMode.COALESCE, MissedStepPolicy.ZERO, epoch, snapshotBytes),
                "room-" + roomId);
        getContext().watchWith(room, new RoomTerminated(roomId));
        roomsByRoomId.put(roomId, room);
        return room;
    }

    /** Empty/unreadable/rejected-by-guardrails all fall back to SOLO -- a CMS bug or a room with
     * nothing provisioned must never crash a join, only ever produce the same safe default this
     * path already produced before Task 11 had a provisioning source at all. */
    private GameDefinition resolveProvisionedDefinition(String roomId, byte[] definitionJsonBytes) {
        if (definitionJsonBytes == null) {
            return GameDefinition.defaultSoloDefinition();
        }
        try {
            return definitionMapper.fromJsonBytes(definitionJsonBytes);
        } catch (DefinitionRejectedException e) {
            log.warn("room {}: provisioned GameDefinition invalid, falling back to SOLO: {}", roomId, e.getMessage());
            return GameDefinition.defaultSoloDefinition();
        }
    }

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
        return replyActorsByChannel.computeIfAbsent(channel, ch -> {
            ActorRef<GameMessage> replyActor =
                    getContext().spawnAnonymous(ChannelReplyActor.create(ch, roomOwnership.ownerPodId(roomId)));
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
