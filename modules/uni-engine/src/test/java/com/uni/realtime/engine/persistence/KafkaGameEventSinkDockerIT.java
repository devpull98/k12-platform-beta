package com.uni.realtime.engine.persistence;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 18's outstanding "chưa làm": {@link KafkaGameEventSink}'s own javadoc says "NOT verified
 * against a real Kafka broker" -- this proves it, for the first time, against the real
 * single-broker KRaft Kafka in {@code docker-compose.dev.yml} (published on {@code localhost:29092}
 * via the {@code PLAINTEXT_HOST} listener specifically added for this).
 *
 * <p>Deliberately does NOT go through {@code RoomActor}/{@code GameEventPublisher}/the wire
 * protocol at all -- Phase 1 has no external way to drive a room into {@code PLAYING} phase (see
 * {@code DockerComposeResyncIT}'s javadoc in {@code uni-e2e}), so proving the full
 * SUBMIT_ANSWER-over-the-wire-to-Kafka path from outside the engine process is not possible. This
 * test instead proves the one thing that actually was unverified: {@link KafkaGameEventSink}
 * itself is a correctly-configured Kafka producer that a real broker accepts records from.
 *
 * <p>Run it with:
 * <pre>
 *   docker compose -f docker-compose.dev.yml up -d --build
 *   RUN_DOCKER_IT=true mvn -pl :uni-engine test -Dtest=KafkaGameEventSinkDockerIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_IT", matches = "true")
class KafkaGameEventSinkDockerIT {

    private static final String BOOTSTRAP_SERVERS = "localhost:29092";
    private static final String TOPIC = "game.events.v1";

    @Test
    void should_deliverAPublishedEvent_toTheRealKafkaBroker() {
        String key = "docker-it-" + UUID.randomUUID();
        byte[] payload = "docker-it-payload".getBytes(StandardCharsets.UTF_8);

        KafkaGameEventSink sink = new KafkaGameEventSink(BOOTSTRAP_SERVERS, TOPIC);
        try {
            sink.send(key, payload);
        } finally {
            sink.close();
        }

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "docker-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (key.equals(record.key())) {
                        assertThat(record.value()).isEqualTo(payload);
                        return;
                    }
                }
            }
        }
        throw new AssertionError("published event with key " + key + " never appeared on topic " + TOPIC + " within 15s");
    }
}
