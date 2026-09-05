package com.uni.realtime.engine;

import com.uni.realtime.observability.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EngineApplicationTests {

    @Autowired
    private NotificationService notificationService;

    @Test
    void contextLoadsWithSharedObservabilityBeans() {
        assertThat(notificationService).isNotNull();
    }
}
