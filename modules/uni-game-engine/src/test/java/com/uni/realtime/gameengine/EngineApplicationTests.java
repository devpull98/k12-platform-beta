package com.uni.realtime.gameengine;

import com.uni.realtime.gameengine.boot.EngineNetworkLifecycle;
import com.uni.realtime.observability.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EngineApplicationTests {

    @Autowired
    private NotificationService notificationService;

    /**
     * room-store (Valkey) is now a hard dependency of {@link EngineNetworkLifecycle#run} (no more
     * {@code ModuloRoomOwnership} fallback) -- mocked out here so this plain context-load test
     * doesn't need a real Valkey instance. Real wiring is exercised by the Docker ITs
     * (RUN_DOCKER_IT=true) and WalkingSkeletonTest, not this smoke test.
     */
    @MockitoBean
    private EngineNetworkLifecycle engineNetworkLifecycle;

    @Test
    void contextLoadsWithSharedObservabilityBeans() {
        assertThat(notificationService).isNotNull();
    }
}
