package com.uni.realtime.websocketgateway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MetricsConfiguration {

    @Bean
    GatewayMetrics gatewayMetrics(MeterRegistry meterRegistry) {
        return new GatewayMetrics(meterRegistry);
    }
}
