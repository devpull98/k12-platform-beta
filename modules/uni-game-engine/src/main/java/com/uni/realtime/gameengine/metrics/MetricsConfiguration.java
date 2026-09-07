package com.uni.realtime.gameengine.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task 12: {@link EngineMetrics} must exist from process start so {@code /actuator/prometheus}
 * shows {@code actor_processing_latency}, {@code actor_mailbox_depth} and
 * {@code channel_not_writable_total} before any room does -- registering it as a Spring bean
 * here, against the auto-configured {@link MeterRegistry}, is what actually closes that gap.
 * Starting real {@code RoomActor}s or the internal frame channel server (which would consume
 * this bean) is Task 13's job, not this one's.
 */
@Configuration
class MetricsConfiguration {

    @Bean
    EngineMetrics engineMetrics(MeterRegistry meterRegistry) {
        return new EngineMetrics(meterRegistry);
    }
}
