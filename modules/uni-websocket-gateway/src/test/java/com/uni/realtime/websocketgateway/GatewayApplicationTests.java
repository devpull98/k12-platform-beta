package com.uni.realtime.websocketgateway;

import com.uni.realtime.observability.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GatewayApplicationTests {

    @Autowired
    private NotificationService notificationService;

    /**
     * The gateway scans one package up so it inherits the shared observability beans from
     * {@code common}. If that scan is ever narrowed back to the gateway package, alerting
     * silently stops working while everything still starts fine — hence asserting on a
     * bean from the other module rather than just that the context loads.
     */
    @Test
    void contextLoadsWithSharedObservabilityBeans() {
        assertThat(notificationService).isNotNull();
    }
}
