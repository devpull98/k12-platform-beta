package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.SubmitAnswer;
import com.uni.realtime.protocol.TeacherCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 verification: the wiring that Task 2's note and Task 9's note both deferred here --
 * spawning a {@code RoomActor} on first join, routing a reply back to the connection a message
 * arrived on, and fanning a broadcast out to every connection subscribed to a room.
 *
 * <p>Uses a real {@link ActorTestKit} (RoomActor's timers are real, per Task 3) with plain
 * {@link ModuloRoomOwnership} for a single pod. Each "connection" is an {@link EmbeddedChannel}
 * whose outbound writes are captured into a {@link BlockingQueue} via a small interceptor --
 * needed because {@code RoomSupervisor} writes to it from an actor dispatcher thread, not the
 * test thread, so a direct {@code channel.readOutbound()} would race.
 *
 * <p>A join is followed by exactly two messages to the joiner's own connection: the direct full
 * snapshot ({@code JoinRoom}'s reply) and, moments later, that same join's own coalescing flush
 * (Task 3's {@code onJoinRoom} always calls {@code scheduleFlushIfDirty()}, and the joiner is
 * also now a broadcast subscriber for that room) -- a real but harmless redundancy, not a bug
 * this task introduces. {@link FakeConnection#takeMatching} looks past it instead of assuming a
 * fixed message count or order.
 */
class RoomSupervisorTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void initSystem() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void shutdownSystem() {
        testKit.shutdownTestKit();
    }

    @Test
    void should_spawnRoomAndReplyFullSnapshot_when_joinRoomDispatched() throws Exception {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        FakeConnection connection = new FakeConnection();

        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-1", "student-1", "Alice"), connection.channel));

        GameMessage reply = connection.takeMatching("a full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
        assertThat(reply.getInternal().getOwnerPodId()).isEqualTo("engine-1");
    }

    @Test
    void should_routeAnswerAck_toTheConnectionThatSubmitted() throws Exception {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        FakeConnection connection = new FakeConnection();
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-2", "student-1", "Alice"), connection.channel));
        connection.drainSettled();
        startGameAndQuestion(supervisor, "room-2");

        supervisor.tell(new RoomSupervisor.Dispatch(submitAnswer("room-2", "student-1", 1L, "q-1"), connection.channel));

        GameMessage ack = connection.takeMatching("an ANSWER_ACK", m -> m.getType() == MessageType.ANSWER_ACK);
        assertThat(ack.getAnswerAck().getAccepted()).isTrue();
        assertThat(ack.getInternal().getDeliveryClass().name()).isEqualTo("CRITICAL");
    }

    @Test
    void should_fanOutBroadcast_toEveryConnectionSubscribedToTheRoom() throws Exception {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        FakeConnection alice = new FakeConnection();
        FakeConnection bob = new FakeConnection();
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-3", "student-alice", "Alice"), alice.channel));
        alice.drainSettled();

        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-3", "student-bob", "Bob"), bob.channel));

        // Bob joining is a roster change Alice's connection must also learn about (§6.2) --
        // proof that broadcast fan-out, not just direct reply, is wired.
        GameMessage aliceSawBobJoin = alice.takeMatching("Bob's join reflected to Alice",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT
                        && m.getRoomStateSnapshot().getPlayersList().stream()
                                .anyMatch(p -> p.getStudentId().equals("student-bob")));
        assertThat(aliceSawBobJoin.getRoomStateSnapshot().getFull()).isFalse();
    }

    @Test
    void should_stopFanningOutToAConnection_when_itsChannelCloses() throws Exception {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        FakeConnection alice = new FakeConnection();
        FakeConnection bob = new FakeConnection();
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-4", "student-alice", "Alice"), alice.channel));
        alice.drainSettled();

        supervisor.tell(new RoomSupervisor.ChannelClosed(alice.channel));
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-4", "student-bob", "Bob"), bob.channel));
        bob.drainSettled();

        assertThat(alice.queue.poll(500, TimeUnit.MILLISECONDS))
                .as("a closed connection must not still be fanned out to")
                .isNull();
    }

    @Test
    void should_spawnFreshRoom_when_roomIdReusedAfterThePreviousRoomEnded() throws Exception {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        FakeConnection first = new FakeConnection();
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-5", "student-1", "Alice"), first.channel));
        first.drainSettled();

        var probe = testKit.<ActorRef<RoomActor.Command>>createTestProbe();
        supervisor.tell(new RoomSupervisor.GetRoomActor("room-5", probe.getRef()));
        ActorRef<RoomActor.Command> firstRoom = probe.receiveMessage();
        firstRoom.tell(new RoomActor.EndGame());

        // Without Task 13's RoomTerminated cleanup, RoomSupervisor would still hold this dead
        // ActorRef in roomsByRoomId, and the rejoin below would dead-letter instead of replying.
        var terminationProbe = testKit.<Object>createTestProbe();
        terminationProbe.expectTerminated(firstRoom, java.time.Duration.ofSeconds(3));

        FakeConnection second = new FakeConnection();
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-5", "student-2", "Bob"), second.channel));

        GameMessage reply = second.takeMatching("a fresh room's full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
        assertThat(reply.getRoomStateSnapshot().getPlayersList())
                .as("a fresh RoomActor must not remember the previous room's roster")
                .extracting(p -> p.getStudentId())
                .containsExactly("student-2");
    }

    @Test
    void should_deliverAllQueuedJoins_afterOneSnapshotLoad_when_theyArriveBeforeItResolves() throws Exception {
        RoomOwnership ownsEverything = new ModuloRoomOwnership("engine-1", List.of("engine-1"));
        ControllableSnapshotStore store = new ControllableSnapshotStore();
        ActorRef<RoomSupervisor.Command> supervisor = testKit.spawn(RoomSupervisor.create(
                ownsEverything, FormulaScoreCalculator.binaryChoice(),
                new EngineMetrics(new SimpleMeterRegistry()), Clock.systemUTC(), store));
        FakeConnection alice = new FakeConnection();
        FakeConnection bob = new FakeConnection();

        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-6", "student-alice", "Alice"), alice.channel));
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-6", "student-bob", "Bob"), bob.channel));
        awaitLoadCallsAtLeast(store, 1);
        assertThat(store.loadCalls.get()).as("both joins for the same room must share ONE load").isEqualTo(1);
        store.resolve(Optional.empty());

        alice.takeMatching("Alice's full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
        bob.takeMatching("Bob's join reflected somewhere",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT);
        assertThat(store.loadCalls.get()).as("resolving must not trigger a second load").isEqualTo(1);
    }

    @Test
    void should_markDisconnected_when_studentLeftDispatched() throws Exception {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        FakeConnection connection = new FakeConnection();
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-7", "student-1", "Alice"), connection.channel));
        connection.drainSettled();

        supervisor.tell(new RoomSupervisor.Dispatch(studentLeft("room-7", "student-1"), connection.channel));

        connection.takeMatching("a delta showing student-1 disconnected",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT
                        && m.getRoomStateSnapshot().getPlayersList().stream()
                                .anyMatch(p -> p.getStudentId().equals("student-1") && !p.getConnected()));
    }

    @Test
    void should_beNoOp_when_studentLeftDispatchedForARoomNotSpawnedOnThisPod() {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        // No prior JOIN_ROOM for "room-8" on this pod -- must not throw or dead-letter loudly,
        // there is simply nothing to update.
        supervisor.tell(new RoomSupervisor.Dispatch(studentLeft("room-8", "student-1"), new FakeConnection().channel));
    }

    @Test
    void should_routeKickStudentToRoomActor_when_teacherCommandDispatched() throws Exception {
        ActorRef<RoomSupervisor.Command> supervisor = spawnSupervisor();
        FakeConnection connection = new FakeConnection();
        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-9", "student-1", "Alice"), connection.channel));
        connection.drainSettled();

        supervisor.tell(new RoomSupervisor.Dispatch(
                teacherCommand("room-9", TeacherCommand.Command.KICK_STUDENT, "student-1"), connection.channel));

        connection.takeMatching("a STUDENT_KICKED notice addressed to student-1",
                m -> m.getType() == MessageType.STUDENT_KICKED && m.getStudentId().equals("student-1"));
    }

    @Test
    void should_restoreRosterFromAWrappedSnapshot_when_loadResolvesWithRealEnvelopeBytes() throws Exception {
        // Real-infra chaos test finding: RoomSnapshotStore#load returns the WRAPPED envelope
        // (SnapshotEnvelope.wrap output), never the bare RoomState.serializeSnapshot() payload
        // ControllableSnapshotStore's OTHER test above (should_deliverAllQueuedJoins...) happens
        // to use Optional.empty(), which never exercised this distinction. Build the bytes the
        // exact same way RoomActor.maybeSnapshot really does, to prove onSnapshotLoaded unwraps
        // before restoring instead of feeding the envelope straight to RoomState.restore.
        RoomState priorRoomState = new RoomState("room-preexisting", Clock.systemUTC(), FormulaScoreCalculator.binaryChoice());
        priorRoomState.joinRoom("student-veteran", "Veteran");
        byte[] wrappedSnapshot = SnapshotEnvelope.wrap(1L, priorRoomState.serializeSnapshot()).orElseThrow();

        RoomOwnership ownsEverything = new ModuloRoomOwnership("engine-1", List.of("engine-1"));
        ControllableSnapshotStore store = new ControllableSnapshotStore();
        ActorRef<RoomSupervisor.Command> supervisor = testKit.spawn(RoomSupervisor.create(
                ownsEverything, FormulaScoreCalculator.binaryChoice(),
                new EngineMetrics(new SimpleMeterRegistry()), Clock.systemUTC(), store));
        FakeConnection newcomer = new FakeConnection();

        supervisor.tell(new RoomSupervisor.Dispatch(joinRoom("room-preexisting", "student-newcomer", "Newcomer"), newcomer.channel));
        awaitLoadCallsAtLeast(store, 1);
        store.resolve(Optional.of(wrappedSnapshot));

        GameMessage reply = newcomer.takeMatching("a full snapshot including the restored veteran",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
        assertThat(reply.getRoomStateSnapshot().getPlayersList())
                .as("a genuinely NEW room would have no roster to restore from")
                .extracting(p -> p.getStudentId())
                .contains("student-veteran");
    }

    private static void awaitLoadCallsAtLeast(ControllableSnapshotStore store, int expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (store.loadCalls.get() < expected && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
        }
    }

    private ActorRef<RoomSupervisor.Command> spawnSupervisor() {
        RoomOwnership ownsEverything = new ModuloRoomOwnership("engine-1", List.of("engine-1"));
        return testKit.spawn(RoomSupervisor.create(ownsEverything, FormulaScoreCalculator.binaryChoice(),
                new EngineMetrics(new SimpleMeterRegistry()), Clock.systemUTC()));
    }

    /** Load never resolves until the test calls {@link #resolve}, so joins queue up behind it deterministically. */
    private static final class ControllableSnapshotStore implements RoomSnapshotStore {
        private final AtomicInteger loadCalls = new AtomicInteger();
        private CompletableFuture<Optional<byte[]>> pending;

        @Override
        public synchronized CompletableFuture<SnapshotWriteResult> save(String roomId, long epoch, byte[] envelopeBytes) {
            return CompletableFuture.completedFuture(SnapshotWriteResult.ACCEPTED);
        }

        @Override
        public synchronized CompletableFuture<Optional<byte[]>> load(String roomId) {
            loadCalls.incrementAndGet();
            pending = new CompletableFuture<>();
            return pending;
        }

        synchronized void resolve(Optional<byte[]> value) {
            pending.complete(value);
        }
    }

    private void startGameAndQuestion(ActorRef<RoomSupervisor.Command> supervisor, String roomId) throws Exception {
        var probe = testKit.<ActorRef<RoomActor.Command>>createTestProbe();
        supervisor.tell(new RoomSupervisor.GetRoomActor(roomId, probe.getRef()));
        ActorRef<RoomActor.Command> room = probe.receiveMessage();
        room.tell(new RoomActor.StartGame());
        room.tell(new RoomActor.StartQuestion("q-1", 25_000, List.of("a")));
    }

    private static GameMessage joinRoom(String roomId, String studentId, String displayName) {
        return GameMessage.newBuilder()
                .setType(MessageType.JOIN_ROOM)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .setJoinRoom(JoinRoom.newBuilder().setDisplayName(displayName))
                .build();
    }

    private static GameMessage submitAnswer(String roomId, String studentId, long sequence, String questionId) {
        return GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .setSequence(sequence)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId(questionId).addAnswerIds("a"))
                .build();
    }

    /** No oneof payload -- {@code RoomRouteHandler} synthesizes this on {@code channelInactive}. */
    private static GameMessage studentLeft(String roomId, String studentId) {
        return GameMessage.newBuilder()
                .setType(MessageType.STUDENT_LEFT)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .build();
    }

    private static GameMessage teacherCommand(String roomId, TeacherCommand.Command command, String targetStudentId) {
        return GameMessage.newBuilder()
                .setType(MessageType.TEACHER_COMMAND)
                .setRoomId(roomId)
                .setTeacherCommand(TeacherCommand.newBuilder()
                        .setCommand(command)
                        .setTargetStudentId(targetStudentId))
                .build();
    }

    /** One fake Gateway-pod connection: an {@link EmbeddedChannel} whose outbound writes land in a queue. */
    private static final class FakeConnection {
        final BlockingQueue<GameMessage> queue = new LinkedBlockingQueue<>();
        final EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                queue.add((GameMessage) msg);
                promise.setSuccess();
            }
        });

        GameMessage takeMatching(String description, Predicate<GameMessage> predicate) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadlineNanos) {
                GameMessage message = queue.poll(200, TimeUnit.MILLISECONDS);
                if (message != null && predicate.test(message)) {
                    return message;
                }
            }
            throw new AssertionError("expected " + description + " within 2s, none arrived");
        }

        /** Waits for in-flight messages to land, then discards everything currently queued. */
        void drainSettled() throws InterruptedException {
            while (queue.poll(300, TimeUnit.MILLISECONDS) != null) {
                // keep discarding until nothing new arrives for a full window
            }
        }
    }
}
