package com.uni.realtime.e2e.docker;

import com.uni.realtime.e2e.support.SimulatedStudentClient;
import com.uni.realtime.gateway.auth.TicketClaims;
import com.uni.realtime.gateway.auth.dev.DevTicketCodec;
import com.uni.realtime.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * PH-1/PH-3/G1a-c stand-in, proven against the REAL {@code docker-compose.dev.yml} stack (gateway
 * + 2 engine pods + room-store + Kafka, all real containers/processes/networking) -- never runs during
 * a normal {@code mvn clean install} (no Docker dependency for routine builds), only when
 * explicitly opted into.
 *
 * <p>Run it with:
 * <pre>
 *   mvn clean package -DskipTests
 *   docker compose -f docker-compose.dev.yml up -d --build
 *   RUN_DOCKER_IT=true mvn -pl :uni-e2e test -Dtest=DockerComposeResyncIT
 * </pre>
 *
 * <p><b>Scope, honestly stated:</b> Phase 1 has no external wire message to start a game/question
 * ({@code WalkingSkeletonTest}'s own javadoc notes this -- the only way to do it is
 * {@code RoomSupervisor.GetRoomActor}, an in-process test hook unreachable from outside the
 * engine's own JVM). Since this test runs against engine pods in SEPARATE containers, it cannot
 * drive a room into {@code PLAYING} phase, so it cannot exercise {@code SUBMIT_ANSWER} scoring or
 * prove "no double-scoring on RESYNC" the way {@code RoomActorResyncTest} does in-process. What
 * this test proves INSTEAD, for the first time over real infrastructure rather than one JVM's
 * loopback sockets: (1) the dev ticket verifier (G1a/G1c stand-in) actually verifies a real
 * HMAC-signed ticket end to end through a real Netty pipeline; (2) two rooms hashing to
 * DIFFERENT engine pods (§7.3 modulo ownership) both route correctly, proving the gateway's
 * learned {@code owner_pod_id} routing works across real containers, not just within one process;
 * (3) the RESYNC wire plumbing (dispatch → {@code RoomActor.onResync} → personal full snapshot
 * reply) survives a real disconnect/reconnect over real sockets without crashing.
 */
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_IT", matches = "true")
class DockerComposeResyncIT {

    private static final int GATEWAY_PORT = 9000;
    private static final DevTicketCodec CODEC = new DevTicketCodec(
            DevTicketCodec.DEFAULT_DEV_SECRET.getBytes(StandardCharsets.UTF_8), Clock.systemUTC());

    private SimulatedStudentClient clientA;
    private SimulatedStudentClient clientB;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (clientA != null) clientA.close();
        if (clientB != null) clientB.close();
    }

    @Test
    void should_routeAcrossPodsAndSurviveResync_againstTheRealDockerStack() throws Exception {
        // floorMod(hashCode, 2): "room-docker-a" -> engine-0, "room-docker-b" -> engine-1
        // (verified via jshell before writing this test -- ModuloRoomOwnership.ownerPodId's
        // exact rule, §7.3/ADR-007).
        String ticketA = CODEC.sign(
                new TicketClaims("student-docker-a", "room-docker-a", "session-docker-a", List.of("student")),
                Duration.ofMinutes(5));
        String ticketB = CODEC.sign(
                new TicketClaims("student-docker-b", "room-docker-b", "session-docker-b", List.of("student")),
                Duration.ofMinutes(5));

        clientA = SimulatedStudentClient.connect(GATEWAY_PORT);
        clientB = SimulatedStudentClient.connect(GATEWAY_PORT);
        // A brand-new room's FIRST JOIN_ROOM can legitimately land on the wrong pod via the
        // gateway's round-robin guess (§4.5 step 2) -- RoomOwnershipHandler's own javadoc
        // documents that Phase 1 drops that frame rather than forwarding it internally, relying
        // on client retry instead. See SimulatedStudentClient.joinRoomWithRetry's javadoc: this
        // is exactly the real-world condition this Docker stack (2 engine pods) surfaces for the
        // first time -- WalkingSkeletonTest/RoomActorResyncTest never hit it with only one pod.
        clientA.joinRoomWithRetry(ticketA, "Alice", 5, 2_000);
        clientB.joinRoomWithRetry(ticketB, "Bob", 5, 2_000);

        // RESYNC's wire plumbing over the real stack -- see class javadoc for why this cannot
        // also prove scoring correctness the way RoomActorResyncTest does in-process.
        String reconnectTicketA = CODEC.sign(
                new TicketClaims("student-docker-a", "room-docker-a", "session-docker-a", List.of("student")),
                Duration.ofMinutes(5));
        clientA.simulateDisconnectAndReconnect("room-docker-a", reconnectTicketA, "Alice");
        clientA.takeMatching("Alice's post-RESYNC full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
    }
}
