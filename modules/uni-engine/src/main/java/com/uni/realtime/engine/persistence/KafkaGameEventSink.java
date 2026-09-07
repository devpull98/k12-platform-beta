package com.uni.realtime.engine.persistence;

import com.uni.realtime.engine.events.GameEventSink;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;

/**
 * Task 18: the real, Kafka-backed {@link GameEventSink}.
 *
 * <p><b>NOT verified against a real Kafka broker</b> -- no broker available in this development
 * environment, same caveat already carried by {@code RedisRoomLeaseStore}/
 * {@code RedisSnapshotStore}. Do not point this at staging/production before it has run
 * end-to-end.
 *
 * <p>{@code max.block.ms = 0} (system-architecture.md §9.3 Rủi ro 6, mitigation #1) is the one
 * setting this class exists to guarantee is never accidentally left at its 60s default --
 * {@link com.uni.realtime.engine.events.GameEventPublisher}'s dedicated worker thread already
 * isolates {@code RoomActor} from this call, but a producer that blocks for 60s still starves
 * every other event queued behind it on that same worker, so the producer itself must not block
 * either. {@code acks = 1} matches {@code KafkaLogAppender}'s existing choice in
 * {@code uni-observability} -- analytics/audit data, not the scoring path.
 */
public final class KafkaGameEventSink implements GameEventSink {

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public KafkaGameEventSink(String bootstrapServers, String topic) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "0");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "uni-engine-game-events");
        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public void send(String partitionKey, byte[] payload) {
        // Fire-and-forget: GameEventPublisher's worker thread already isolates the caller, and a
        // per-record ack callback would have nothing actionable to do differently on success vs
        // in-flight -- errors surface through the exception KafkaProducer.send itself can throw
        // synchronously (e.g. buffer exhaustion under max.block.ms=0), which the worker already
        // catches and logs.
        producer.send(new ProducerRecord<>(topic, partitionKey, payload));
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(2));
    }
}
