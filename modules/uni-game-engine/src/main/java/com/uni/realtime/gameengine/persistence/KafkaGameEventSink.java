package com.uni.realtime.gameengine.persistence;

import com.uni.realtime.gameengine.events.GameEventSink;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Properties;

public final class KafkaGameEventSink implements GameEventSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaGameEventSink.class);

    private static final Duration METADATA_WARMUP_TIMEOUT = Duration.ofSeconds(5);

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
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "uni-game-engine-game-events");
        this.producer = new KafkaProducer<>(props);
        warmUpMetadata();
    }

    private void warmUpMetadata() {
        long deadlineNanos = System.nanoTime() + METADATA_WARMUP_TIMEOUT.toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadlineNanos) {
            try {
                producer.partitionsFor(topic);
                return;
            } catch (Exception e) {
                lastFailure = e;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("could not warm up Kafka metadata for topic {} at startup within {} -- "
                        + "the first real event may still be dropped, see send()'s javadoc",
                topic, METADATA_WARMUP_TIMEOUT, lastFailure);
    }

    @Override
    public void send(String partitionKey, byte[] payload) {
        producer.send(new ProducerRecord<>(topic, partitionKey, payload), (metadata, exception) -> {
            if (exception != null) {
                log.warn("game event publish failed for key {}", partitionKey, exception);
            }
        });
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(2));
    }
}
