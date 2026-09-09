package com.uni.realtime.gameengine.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MetricsConfiguration {

    @Bean
    EngineMetrics engineMetrics(MeterRegistry meterRegistry) {
        return new EngineMetrics(meterRegistry);
    }
}
