package com.uni.realtime.e2e.docker;

import com.uni.realtime.e2e.support.SimulatedStudentClient;
import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.dev.DevJoinTokenCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents, via a deliberately {@code @Disabled} red test, the Gateway-side gap found while
 * auditing plan.md Task 14 (2026-09-08 review): {@code LeaseBasedRoomOwnership} makes room
 * ownership independent of pod count on the Engine side, but a genuinely NEW Engine pod -- one
 * absent from {@code uni.gateway.engine.pods} when Gateway booted -- is unreachable no matter what
 * ownership algorithm Engine runs, because Gateway never dials it.
 *
 * <p>Root cause, confirmed by reading the code (not guessed): {@code GatewayNetworkLifecycle.run()}
 * reads {@code uni.gateway.engine.pods} exactly ONCE at Spring Boot startup and calls
 * {@code FrameChannelClient.connect(...)} synchronously for each entry. {@link
 * com.uni.realtime.websocketgateway.routing.FrameChannelClient}'s {@code knownPods}/{@code
 * podChannels} only ever shrink (on disconnect) -- nothing ever adds an entry after boot. This is
 * the same limitation {@code DockerComposeChaosIT}'s teardown javadoc already names ("no retry
 * loop exists today") for the opposite direction (reconnecting a pod that came back); here it
 * blocks a pod Gateway never knew about in the first place.
 *
 * <p>{@code docker-compose.dev.yml}'s {@code engine-2} service exists ONLY for this test: it is
 * deliberately left out of {@code gateway}/{@code gateway-1}'s {@code ENGINE_PODS} env var, so
 * starting it mid-test simulates an SRE adding Engine capacity without restarting Gateway --
 * exactly the "scale-up giữa phiên" scenario plan.md Task 14 left untested (see its AC: "Điều CHƯA
 * test: thêm pod thứ 3 giữa lúc 2 pod kia đang chạy phòng thật").
 *
 * <p><b>This test is expected to FAIL today</b> -- that failure IS the documentation, and is why
 * it stays {@code @Disabled} (an always-red test in the default `mvn test` run would break the
 * build per CLAUDE.md's own rule that a real failure is a build failure, not a log line to skim
 * past). Re-enable it once plan.md Task 21 (Gateway dynamic engine-pod discovery / ENGINE_PODS
 * hot-reload) ships; the assertion itself should not need to change.
 *
 * <p>Run it (after removing {@code @Disabled}) with:
 * <pre>
 *   mvn clean package -DskipTests
 *   docker compose -f docker-compose.dev.yml up -d --build room-store kafka engine-0 engine-1 gateway gateway-1
 *   RUN_DOCKER_IT=true mvn -pl :uni-e2e test -Dtest=DockerComposeScaleUpIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_IT", matches = "true")
class DockerComposeScaleUpIT {

    private static final int GATEWAY_PORT = 9000;
    private static final DevJoinTokenCodec CODEC = new DevJoinTokenCodec(
            DevJoinTokenCodec.DEFAULT_DEV_SECRET.getBytes(StandardCharsets.UTF_8), Clock.systemUTC());

    private boolean startedEngine2;

    @AfterEach
    void tearDown() throws Exception {
        // Graceful stop, not kill -- engine-2 was never "crashed", just leave the stack as found
        // for whichever test runs next.
        if (startedEngine2) {
            DockerComposeControl.stop("engine-2");
        }
    }

    @Test
    @Disabled("Documents plan.md Task 21 (Gateway dynamic engine-pod discovery). Currently RED by "
            + "design: uni.gateway.engine.pods is a static list read once at Gateway boot "
            + "(GatewayNetworkLifecycle.run()), so a pod started afterward is never dialed and "
            + "never receives traffic. Re-enable once Task 21 ships a discovery/hot-reload "
            + "mechanism -- this assertion should then go green unchanged.")
    void should_routeNewRoomsToAFreshlyStartedEnginePod_withoutRestartingGateway() throws Exception {
        DockerComposeControl.up("engine-2");
        startedEngine2 = true;
        DockerComposeControl.awaitHealthy("engine-2", Duration.ofSeconds(30));

        // Routing for a cache-miss room is round-robin across Gateway's KNOWN pod connections --
        // deterministic, not probabilistic, once that set is fixed. One new room is already
        // enough to prove the point; five just guards against this test itself misreading how
        // round-robin distributes across the two pods Gateway actually knows about.
        boolean sawEngine2AsOwner = false;
        for (int i = 0; i < 5; i++) {
            String roomId = "room-scaleup-" + i;
            String studentId = "student-scaleup-" + i;
            String joinToken = CODEC.sign(
                    new JoinTokenClaims(studentId, roomId, "session-scaleup-" + i, List.of("student")),
                    Duration.ofMinutes(5));

            SimulatedStudentClient client = SimulatedStudentClient.connect(GATEWAY_PORT);
            try {
                client.joinRoomWithRetry(joinToken, "Scale Student " + i, 5, 2_000);
                if ("engine-2".equals(ownerOf(roomId))) {
                    sawEngine2AsOwner = true;
                }
            } finally {
                client.close();
            }
        }

        assertThat(sawEngine2AsOwner)
                .as("a freshly started Engine pod should eventually receive new rooms without a "
                        + "Gateway restart -- today it never does, because ENGINE_PODS is read "
                        + "once at boot (see class javadoc, plan.md Task 21)")
                .isTrue();
    }

    /** Mirrors {@code DockerComposeChaosIT#ownerOf} -- reads {@code room:owner:<room_id>} straight from the real room-store (Valkey), ground truth over any hashing convention. */
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
}
