package com.uni.realtime.e2e.docker;

import com.uni.realtime.e2e.support.SimulatedStudentClient;
import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.dev.DevJoinTokenCodec;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.PlayerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaos scenarios against the REAL {@code docker-compose.dev.yml} stack, closing the two AC still
 * left {@code [ ]} in plan.md after {@code DockerComposeResyncIT}/Task 20: Task 13's "kill an
 * Engine pod -> CONNECTION_DEGRADED, socket stays open" and Task 14's "pod crash -> another pod
 * wins the lease and restores the Hot Snapshot". Both mechanisms already had production wiring
 * and unit-level fake-based tests before this file; what was missing was proof against a real
 * killed container, not just a fake disconnect callback.
 *
 * <p>Run it with:
 * <pre>
 *   mvn clean package -DskipTests
 *   docker compose -f docker-compose.dev.yml up -d --build
 *   RUN_DOCKER_IT=true mvn -pl :uni-e2e test -Dtest=DockerComposeChaosIT
 * </pre>
 *
 * <p>{@link #should_recoverRoomOnAnotherPod_when_itsOwningEngineIsKilled} deliberately does NOT
 * (and per {@code DockerComposeResyncIT}'s own javadoc, cannot) verify score recovery -- Phase 1
 * has no external wire message to start a question from outside the Engine JVM, so this proves
 * exactly the AC still open in plan.md Task 14 (lease handoff + roster restore from Hot
 * Snapshot), not the whole scoring pipeline.
 */
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_IT", matches = "true")
class DockerComposeChaosIT {

    private static final Logger log = LoggerFactory.getLogger(DockerComposeChaosIT.class);
    private static final int GATEWAY_PORT = 9000;
    private static final DevJoinTokenCodec CODEC = new DevJoinTokenCodec(
            DevJoinTokenCodec.DEFAULT_DEV_SECRET.getBytes(StandardCharsets.UTF_8), Clock.systemUTC());

    private SimulatedStudentClient client;
    private String killedEngineService;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (killedEngineService != null) {
            // Bringing the Engine container back is NOT enough: FrameChannelClient dials every
            // Engine pod once at Gateway startup and never reconnects one it already marked
            // dead (no retry loop exists today, see DockerComposeControl.restart's javadoc) --
            // without restarting `gateway` too, the NEXT test in this run would only have one
            // reachable Engine pod left, no matter how many containers are actually healthy.
            DockerComposeControl.start(killedEngineService);
            DockerComposeControl.awaitHealthy(killedEngineService, Duration.ofSeconds(30));
            DockerComposeControl.restart("gateway");
            DockerComposeControl.awaitHealthy("gateway", Duration.ofSeconds(30));
        }
    }

    @Test
    void should_notCloseTheSocket_when_itsOwningEngineIsKilled() throws Exception {
        String roomId = "room-chaos-degraded";
        String joinToken = CODEC.sign(
                new JoinTokenClaims("student-chaos-degraded", roomId, "session-chaos-degraded", List.of("student")),
                Duration.ofMinutes(5));

        client = SimulatedStudentClient.connect(GATEWAY_PORT);
        client.joinRoomWithRetry(joinToken, "Chaos Student", 5, 2_000);

        String owningPod = ownerOf(roomId);
        killedEngineService = owningPod;
        DockerComposeControl.kill(owningPod);

        client.takeMatching("CONNECTION_DEGRADED for " + roomId,
                m -> m.getType() == MessageType.CONNECTION_DEGRADED && m.getRoomId().equals(roomId));

        assertThat(client.isOpen())
                .as("§9.7: losing an Engine pod must degrade the room, never close the client's own WebSocket")
                .isTrue();
    }

    @Test
    void should_recoverRoomOnAnotherPod_when_itsOwningEngineIsKilled() throws Exception {
        String roomId = "room-chaos-recover";
        String studentId = "student-chaos-recover";
        String joinToken = CODEC.sign(
                new JoinTokenClaims(studentId, roomId, "session-chaos-recover", List.of("student")),
                Duration.ofMinutes(5));

        client = SimulatedStudentClient.connect(GATEWAY_PORT);
        client.joinRoomWithRetry(joinToken, "Chaos Student", 5, 2_000);

        String originalOwner = ownerOf(roomId);
        awaitSnapshotWritten(roomId);

        killedEngineService = originalOwner;
        // plan.md Task 14's last open AC: measure REAL recovery time to replace the unfounded
        // "10-50ms" system-architecture.md used to state (already removed pending this number).
        long killedAtNanos = System.nanoTime();
        DockerComposeControl.kill(originalOwner);

        // The surviving pod cannot win the lease until it expires server-side
        // (ENGINE_ROOM_STORE_LEASE_TTL_SECONDS, 20s in docker-compose.dev.yml, reset to a full
        // 20s on every renewAll() cycle -- so right up to 20s of real remaining TTL is possible
        // at the moment of the kill, not just "20s since last renewal"). 25 attempts x 3s = 75s
        // gives generous margin over that worst case plus round-robin/detection overhead,
        // observed to matter in practice (a tighter 15x2s=30s window flaked once).
        var fullSnapshot = client.joinRoomWithRetry(joinToken, "Chaos Student", 25, 3_000);
        long recoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - killedAtNanos);

        List<PlayerState> players = fullSnapshot.getRoomStateSnapshot().getPlayersList();
        assertThat(players)
                .as("a genuinely NEW room would have an empty roster -- this must be the RESTORED one")
                .anySatisfy(player -> {
                    assertThat(player.getStudentId()).isEqualTo(studentId);
                    assertThat(player.getDisplayName()).isEqualTo("Chaos Student");
                });

        String newOwner = ownerOf(roomId);
        assertThat(newOwner).as("a different pod must now hold the lease").isNotEqualTo(originalOwner);

        // Logged, not asserted on a bound -- this number is inherently a function of the chosen
        // 20s lease TTL plus wherever in its renewal cycle the kill happened to land (0-20s real
        // remaining TTL, see comment above), not a latency this code could optimize down. The
        // number itself is what plan.md Task 14 needs recorded in system-architecture.md.
        log.info("room {}: recovered on pod {} (was {}) {} ms after the owning pod was killed "
                        + "(lease TTL=20s)", roomId, newOwner, originalOwner, recoveryMs);
    }

    /** Reads {@code room:owner:<room_id>} straight from the real room-store (Valkey) -- ground truth, not a guess from a hashing convention that no longer applies once LeaseBasedRoomOwnership is active. */
    private static String ownerOf(String roomId) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            String owner = DockerComposeControl.execCapture("room-store", "valkey-cli", "GET", "room:owner:" + roomId);
            if (!owner.isBlank() && !owner.equals("(nil)")) {
                return owner;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("room:owner:" + roomId + " never appeared in room-store within 10s");
    }

    private static void awaitSnapshotWritten(String roomId) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            String exists = DockerComposeControl.execCapture("room-store", "valkey-cli", "EXISTS", "room:snap:" + roomId);
            if ("1".equals(exists)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("room:snap:" + roomId + " was never written within 10s");
    }
}
