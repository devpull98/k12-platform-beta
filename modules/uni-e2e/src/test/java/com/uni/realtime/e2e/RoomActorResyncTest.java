package com.uni.realtime.e2e;

import com.uni.realtime.e2e.support.SimulatedStudentClient;
import com.uni.realtime.engine.metrics.EngineMetrics;
import com.uni.realtime.engine.net.FrameChannelServer;
import com.uni.realtime.engine.room.ModuloRoomOwnership;
import com.uni.realtime.engine.room.RoomActor;
import com.uni.realtime.engine.room.RoomOwnership;
import com.uni.realtime.engine.room.RoomSupervisor;
import com.uni.realtime.engine.scoring.FormulaScoreCalculator;
import com.uni.realtime.gateway.auth.TicketClaims;
import com.uni.realtime.gateway.auth.TicketRejectedException;
import com.uni.realtime.gateway.auth.TicketVerifier;
import com.uni.realtime.gateway.fanout.Broadcaster;
import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.gateway.metrics.GatewayMetrics;
import com.uni.realtime.gateway.net.EngineResponseRouter;
import com.uni.realtime.gateway.net.GatewayBootstrap;
import com.uni.realtime.gateway.net.IpAdmissionController;
import com.uni.realtime.gateway.net.StudentHandshakeAdmissionController;
import com.uni.realtime.gateway.routing.FrameChannelClient;
import com.uni.realtime.gateway.routing.RouteCache;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PH-3 / §9.3: proves the new {@code RoomActor.Resync} handling this task added actually works
 * end to end, over real sockets, in-process (no Docker needed -- runs as part of every
 * {@code mvn clean install}). {@code DockerComposeResyncIT} repeats the same shape against a
 * real multi-pod Docker stack.
 *
 * <p>Scenario: two students join a room. The teacher starts a question. Student A submits an
 * answer but the client-side {@code ANSWER_ACK} is never drained -- simulating a network blip
 * that swallowed the ack before the client could act on it -- then disconnects before reading
 * it, reconnects, and sends a real {@code RESYNC} with that submission still in its ring buffer.
 * The room must not double-score it, must reply with exactly one {@code ANSWER_ACK} (the replayed
 * original, per §5.2) plus one full snapshot, and student B -- who never disconnected -- must see
 * no anomaly.
 */
class RoomActorResyncTest {

    private EventLoopGroup gatewayClientGroup;
    private ActorSystem<RoomSupervisor.Command> engineSystem;
    private FrameChannelServer engineServer;
    private GatewayBootstrap gatewayBootstrap;
    private SimulatedStudentClient clientA;
    private SimulatedStudentClient clientB;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (clientA != null) clientA.close();
        if (clientB != null) clientB.close();
        if (gatewayBootstrap != null) gatewayBootstrap.shutdown();
        if (gatewayClientGroup != null) gatewayClientGroup.shutdownGracefully().sync();
        if (engineServer != null) engineServer.shutdown();
        if (engineSystem != null) engineSystem.terminate();
    }

    @Test
    void should_replayPendingSubmissionOnce_when_clientResyncsAfterMissingItsAck() throws Exception {
        RoomOwnership ownsEverything = new ModuloRoomOwnership("engine-0", List.of("engine-0"));
        engineSystem = ActorSystem.create(RoomSupervisor.create(ownsEverything,
                FormulaScoreCalculator.binaryChoice(), new EngineMetrics(new SimpleMeterRegistry()), Clock.systemUTC()), "resync-engine");
        engineServer = new FrameChannelServer(0, ownsEverything,
                (channel, message) -> engineSystem.tell(new RoomSupervisor.Dispatch(message, channel)),
                channel -> engineSystem.tell(new RoomSupervisor.ChannelClosed(channel)),
                new EngineMetrics(new SimpleMeterRegistry()));
        engineServer.start();

        int gatewayPort = startGateway(engineServer.boundPort());

        clientA = SimulatedStudentClient.connect(gatewayPort);
        clientB = SimulatedStudentClient.connect(gatewayPort);
        clientA.send(joinRoom("ticket:student-a:room-resync", "Alice"));
        clientB.send(joinRoom("ticket:student-b:room-resync", "Bob"));
        clientA.takeMatching("Alice's full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
        clientB.takeMatching("Bob's full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());

        startGameAndQuestion("room-resync");

        // Settle both clients past join-triggered deltas (student-a's and student-b's own dirty
        // flags at join) before asserting silence about a specific LATER window -- otherwise a
        // leftover, unrelated pre-existing message would make the final silence check meaningless.
        clientA.clearReceived();
        clientB.clearReceived();

        // Student A submits but never drains the resulting ANSWER_ACK -- it stays in the ring
        // buffer as "unacked" from the client's point of view -- then disconnects.
        long sequence = clientA.submitTrackedAnswer("room-resync", "q-1", List.of("a"));
        clientA.simulateDisconnectAndReconnect("room-resync", "ticket:student-a:room-resync", "Alice");

        // The reconnect's JOIN_ROOM reply and RESYNC's reply both land on the queue; take them
        // in the order the server actually sends them: ANSWER_ACK for the replayed submission,
        // then the resync's own full snapshot.
        GameMessage ack = clientA.takeMatching("the replayed ANSWER_ACK for sequence " + sequence,
                m -> m.getType() == MessageType.ANSWER_ACK && m.getAnswerAck().getAckedSequence() == sequence);
        assertThat(ack.getAnswerAck().getAccepted()).isTrue();
        assertThat(ack.getAnswerAck().getAwardedPoints()).isEqualTo(100);

        GameMessage resyncSnapshot = clientA.takeMatching("the resync full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
        assertThat(resyncSnapshot.getRoomStateSnapshot().getPlayersList())
                .filteredOn(p -> p.getStudentId().equals("student-a"))
                .as("no double-scoring: exactly one award of 100 must be reflected")
                .allMatch(p -> p.getScore() == 100);

        // §5.2: sending the SAME sequence again (e.g. a naive second resync) must still not
        // double-score -- the room's lastSeenSequence table is authoritative regardless of what
        // the client believes.
        clientA.send(GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId("room-resync")
                .setSequence(sequence)
                .setSubmitAnswer(com.uni.realtime.protocol.SubmitAnswer.newBuilder()
                        .setQuestionId("q-1").addAnswerIds("a"))
                .build());
        GameMessage duplicateAck = clientA.takeMatching("the duplicate-sequence ack",
                m -> m.getType() == MessageType.ANSWER_ACK);
        assertThat(duplicateAck)
                .as("§5.2: a replayed sequence returns the ORIGINAL ack verbatim, never a freshly "
                        + "recomputed one -- same total_score, not double-counted")
                .isEqualTo(ack);

        // Student B, who never disconnected, correctly still sees Alice's update fan out to it
        // (§6.2: "the delta must reach EVERY connection in the room, not just the submitter") --
        // that is expected, not an anomaly. What RESYNC must never do is let Bob's view show
        // Alice double-scored, regardless of how many broadcasts the reconnect/resync causes.
        GameMessage bobsViewOfAlice = clientB.takeMatching("Bob's view of Alice's score",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT
                        && m.getRoomStateSnapshot().getPlayersList().stream()
                                .anyMatch(p -> p.getStudentId().equals("student-a") && p.getScore() > 0));
        assertThat(bobsViewOfAlice.getRoomStateSnapshot().getPlayersList())
                .filteredOn(p -> p.getStudentId().equals("student-a"))
                .as("Bob must never see Alice double-scored, no matter how many broadcasts the resync causes")
                .allMatch(p -> p.getScore() == 100);
    }

    private int startGateway(int enginePort) throws InterruptedException {
        RoomRegistry roomRegistry = new RoomRegistry();
        GatewayMetrics gatewayMetrics = new GatewayMetrics(new SimpleMeterRegistry());
        Broadcaster broadcaster = new Broadcaster(roomRegistry, gatewayMetrics);
        EngineResponseRouter responseRouter = new EngineResponseRouter(roomRegistry, broadcaster);
        RouteCache routeCache = new RouteCache();
        gatewayClientGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        FrameChannelClient frameChannelClient = new FrameChannelClient(routeCache, responseRouter::route,
                responseRouter::broadcastConnectionDegraded, gatewayClientGroup, gatewayMetrics);
        frameChannelClient.connect("engine-0", "localhost", enginePort);

        gatewayBootstrap = new GatewayBootstrap(0, new FakeTicketVerifier(), roomRegistry, gatewayMetrics,
                new IpAdmissionController(), new StudentHandshakeAdmissionController(), frameChannelClient);
        gatewayBootstrap.start();
        return gatewayBootstrap.boundPort();
    }

    private void startGameAndQuestion(String roomId) {
        TestProbe<ActorRef<RoomActor.Command>> probe = TestProbe.create(engineSystem);
        engineSystem.tell(new RoomSupervisor.GetRoomActor(roomId, probe.getRef()));
        ActorRef<RoomActor.Command> room = probe.receiveMessage();
        room.tell(new RoomActor.StartGame());
        room.tell(new RoomActor.StartQuestion("q-1", 25_000, List.of("a")));
    }

    private static GameMessage joinRoom(String ticket, String displayName) {
        return GameMessage.newBuilder()
                .setType(MessageType.JOIN_ROOM)
                .setJoinRoom(com.uni.realtime.protocol.JoinRoom.newBuilder().setTicket(ticket).setDisplayName(displayName))
                .build();
    }

    /** Same test-only stand-in {@code WalkingSkeletonTest} uses -- see that class's javadoc for why. */
    private static final class FakeTicketVerifier implements TicketVerifier {
        @Override
        public TicketClaims verify(String ticket) throws TicketRejectedException {
            String[] parts = ticket.split(":");
            if (parts.length != 3 || !parts[0].equals("ticket")) {
                throw new TicketRejectedException("malformed test ticket: " + ticket);
            }
            return new TicketClaims(parts[1], parts[2], "session-" + parts[1], List.of("student"));
        }
    }
}
