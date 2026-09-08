package com.uni.realtime.websocketgateway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task 12: {@link GatewayMetrics} must exist from process start so {@code /actuator/prometheus}
 * shows {@code fanout_latency}, {@code handshake_rate} and {@code channel_not_writable_total}
 * before any real traffic -- registering it as a Spring bean here, against the
 * auto-configured {@link MeterRegistry}, is what actually closes that gap. Starting the real
 * Netty pipeline (which would consume this bean) still needs a real {@code JoinTokenVerifier}
 * (§G1a/G1c, not yet answered) and is Task 13's job, not this one's.
 */
@Configuration
class MetricsConfiguration {

    @Bean
    GatewayMetrics gatewayMetrics(MeterRegistry meterRegistry) {
        return new GatewayMetrics(meterRegistry);
    }
}
