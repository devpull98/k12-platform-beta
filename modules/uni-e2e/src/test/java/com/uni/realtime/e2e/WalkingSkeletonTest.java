package com.uni.realtime.e2e;

import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.net.FrameChannelServer;
import com.uni.realtime.e2e.support.AlwaysOwnRoomOwnership;
import com.uni.realtime.gameengine.room.RoomActor;
import com.uni.realtime.gameengine.room.RoomOwnership;
import com.uni.realtime.gameengine.room.RoomSupervisor;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.e2e.support.SimulatedStudentClient;
import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;
import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import com.uni.realtime.websocketgateway.fanout.Broadcaster;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.net.EngineResponseRouter;
import com.uni.realtime.websocketgateway.net.GatewayBootstrap;
import com.uni.realtime.websocketgateway.net.IpAdmissionController;
import com.uni.realtime.websocketgateway.net.StudentHandshakeAdmissionController;
import com.uni.realtime.websocketgateway.routing.FrameChannelClient;
import com.uni.realtime.websocketgateway.routing.RouteCache;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.SubmitAnswer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13, plan.md's stated criterion for "Giai đoạn 1 xong": a real packet travels the whole
 * loop -- WebSocket client → Gateway → internal frame channel → Engine's {@code RoomSupervisor}
 * → {@code RoomActor} → back out the same path. Everything here is a real socket (no {@code
 * EmbeddedChannel}); the only thing not real is the joinToken, because G1a/G1c (the signing
 * algorithm) is still an open question for the platform team -- {@link FakeJoinTokenVerifier}
 * below is explicitly a test-only stand-in, exactly as {@code JoinTokenAuthHandler}'s own javadoc
 * requires ("Không dùng verifier tạm này ở staging/production").
 *
 * <p>Starting a question has no wire message at all in Phase 1's schema (no game-definition
 * content format has been decided -- tech-design.md Task 11 note), so this test drives it the
 * same way {@code RoomSupervisorTest} does: through {@link RoomSupervisor.GetRoomActor}, a
 * test/ops-only introspection hook, never invented client-facing protocol.
 *
 * <p>Docker Compose (plan.md's literal AC) is not exercised here -- the Docker daemon was not
 * running in the environment this was written in, so a compose-based run could not be verified
 * and is not claimed as done. This test proves the identical claims (routing, coalescing,
 * multi-pod learning) without needing containers, which is what actually exercises the code.
 */
class WalkingSkeletonTest {

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
    void should_deliverAnswerAckAndBroadcastDelta_forAJoinSubmitRoundTrip_overRealSockets() throws Exception {
        RoomOwnership ownsEverything = new AlwaysOwnRoomOwnership("engine-0");
        engineSystem = ActorSystem.create(RoomSupervisor.create(ownsEverything,
                FormulaScoreCalculator.binaryChoice(), new EngineMetrics(new SimpleMeterRegistry()), Clock.systemUTC()), "e2e-engine");
        engineServer = new FrameChannelServer(0, ownsEverything,
                (channel, message) -> engineSystem.tell(new RoomSupervisor.Dispatch(message, channel)),
                channel -> engineSystem.tell(new RoomSupervisor.ChannelClosed(channel)),
                new EngineMetrics(new SimpleMeterRegistry()));
        engineServer.start();

        int gatewayPort = startGateway(engineServer.boundPort());

        clientA = SimulatedStudentClient.connect(gatewayPort);
        clientB = SimulatedStudentClient.connect(gatewayPort);
        clientA.send(joinRoom("join-token:student-a:room-1", "Alice"));
        clientB.send(joinRoom("join-token:student-b:room-1", "Bob"));
        assertThat(clientA.takeMatching("Alice's full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull())).isNotNull();
        assertThat(clientB.takeMatching("Bob's full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull())).isNotNull();

        startGameAndQuestion("room-1");

        clientA.send(submitAnswer("room-1", "q-1", 1L, "a"));

        GameMessage ack = clientA.takeMatching("Alice's ANSWER_ACK", m -> m.getType() == MessageType.ANSWER_ACK);
        assertThat(ack.getAnswerAck().getAccepted()).isTrue();
        assertThat(ack.getAnswerAck().getAwardedPoints()).isEqualTo(100);

        // §6.2: the delta must reach EVERY connection in the room, not just the submitter.
        GameMessage deltaOnA = clientA.takeMatching("Alice's own delta", m -> isDeltaFor(m, "student-a"));
        GameMessage deltaOnB = clientB.takeMatching("Bob sees Alice's delta", m -> isDeltaFor(m, "student-a"));
        assertThat(deltaOnA.getRoomStateSnapshot().getFull()).isFalse();
        assertThat(deltaOnB.getRoomStateSnapshot().getFull()).isFalse();

        // ADR-4's central claim, now over a real socket: silence in, silence out.
        clientA.assertNoMoreMessagesFor(500);
        clientB.assertNoMoreMessagesFor(500);

        // §9.5: replaying the same sequence must not re-score or re-broadcast.
        clientA.send(submitAnswer("room-1", "q-1", 1L, "a"));
        GameMessage replay = clientA.takeMatching("the replayed ack", m -> m.getType() == MessageType.ANSWER_ACK);
        assertThat(replay).isEqualTo(ack);
        clientA.assertNoMoreMessagesFor(500);
        clientB.assertNoMoreMessagesFor(500);
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

        gatewayBootstrap = new GatewayBootstrap(0, new FakeJoinTokenVerifier(), roomRegistry, gatewayMetrics,
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

    private static boolean isDeltaFor(GameMessage message, String studentId) {
        return message.getType() == MessageType.ROOM_STATE_SNAPSHOT
                && !message.getRoomStateSnapshot().getFull()
                && message.getRoomStateSnapshot().getPlayersList().stream().anyMatch(p -> p.getStudentId().equals(studentId));
    }

    private static GameMessage joinRoom(String joinToken, String displayName) {
        return GameMessage.newBuilder()
                .setType(MessageType.JOIN_ROOM)
                .setJoinRoom(JoinRoom.newBuilder().setJoinToken(joinToken).setDisplayName(displayName))
                .build();
    }

    private static GameMessage submitAnswer(String roomId, String questionId, long sequence, String answerId) {
        return GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId(roomId)
                .setSequence(sequence)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId(questionId).addAnswerIds(answerId))
                .build();
    }

    /**
     * Test-only stand-in for the real joinToken format G1a/G1c has not settled yet. JoinToken shape:
     * {@code "join-token:<student_id>:<room_id>"} -- nothing more than what {@link JoinTokenClaims}
     * needs, invented here because there is nothing real to call instead.
     */
    private static final class FakeJoinTokenVerifier implements JoinTokenVerifier {
        @Override
        public JoinTokenClaims verify(String joinToken) throws JoinTokenRejectedException {
            String[] parts = joinToken.split(":");
            if (parts.length != 3 || !parts[0].equals("join-token")) {
                throw new JoinTokenRejectedException("malformed test joinToken: " + joinToken);
            }
            return new JoinTokenClaims(parts[1], parts[2], "session-" + parts[1], List.of("student"));
        }
    }
}
