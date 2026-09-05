package com.uni.realtime.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;

/**
 * Ships encoded log events to a Kafka topic for an external ELK pipeline (Logstash consumes the
 * topic). Not com.github.danielwegener:logback-kafka-appender — that library's last release was
 * 0.2.0 and it's unmaintained, so this talks to kafka-clients directly instead.
 */
public class KafkaLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private Encoder<ILoggingEvent> encoder;
    private String topic;
    private String bootstrapServers;

    private KafkaProducer<String, byte[]> producer;

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Override
    public void start() {
        if (encoder == null || topic == null || bootstrapServers == null) {
            addError("KafkaLogAppender requires encoder, topic and bootstrapServers to be set");
            return;
        }

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        // Cap the block time so a slow/unreachable ELK broker can never stall the calling
        // (possibly virtual) thread for long — logs are best-effort, the purchase path isn't.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1000");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "uni-realtime-log-appender");

        producer = new KafkaProducer<>(props);

        encoder.setContext(getContext());
        if (!encoder.isStarted()) {
            encoder.start();
        }

        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (producer == null) {
            return;
        }

        byte[] payload;
        try {
            payload = encoder.encode(event);
        } catch (Exception e) {
            addError("Failed to encode log event for Kafka", e);
            return;
        }

        try {
            producer.send(new ProducerRecord<>(topic, payload), (metadata, exception) -> {
                if (exception != null) {
                    addError("Failed to publish log event to Kafka topic " + topic, exception);
                }
            });
        } catch (Exception e) {
            addError("Failed to enqueue log event to Kafka", e);
        }
    }

    @Override
    public void stop() {
        if (producer != null) {
            try {
                producer.flush();
                producer.close(Duration.ofSeconds(2));
            } catch (Exception e) {
                addError("Failed to close Kafka producer cleanly", e);
            }
        }
        if (encoder != null) {
            encoder.stop();
        }
        super.stop();
    }
}
