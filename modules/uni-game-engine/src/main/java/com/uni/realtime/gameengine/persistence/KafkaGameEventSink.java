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

/**
 * Task 18: the real, Kafka-backed {@link GameEventSink}.
 *
 * <p>Verified against a real single-broker Kafka (KRaft mode) via {@code docker-compose.dev.yml}
 * and {@code KafkaGameEventSinkDockerIT} (2026-09-07) -- the first time this class has run
 * against a real broker rather than being constructed and never exercised.
 *
 * <p>{@code max.block.ms = 0} (system-architecture.md §9.3 Rủi ro 6, mitigation #1) is the one
 * setting this class exists to guarantee is never accidentally left at its 60s default --
 * {@link com.uni.realtime.gameengine.events.GameEventPublisher}'s dedicated worker thread already
 * isolates {@code RoomActor} from this call, but a producer that blocks for 60s still starves
 * every other event queued behind it on that same worker, so the producer itself must not block
 * either. {@code acks = 1} matches {@code KafkaLogAppender}'s existing choice in
 * {@code uni-observability} -- analytics/audit data, not the scoring path.
 *
 * <p>Docker verification (2026-09-07) found the one real cost of {@code max.block.ms = 0}:
 * without cached topic metadata, {@code producer.send()} cannot fetch it in 0ms and the record is
 * dropped -- which happens to EVERY producer's first-ever send after construction, i.e. on every
 * single engine pod (re)start, not just in a fresh/cold environment. {@link #warmUpMetadata}
 * pre-fetches metadata once at construction (bounded, one-time, inside {@code
 * EngineNetworkLifecycle}'s ApplicationRunner -- never on the actor dispatcher or the publisher's
 * worker thread) so the very first real event is not silently lost.
 */
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

    /**
     * Bounded, one-time, best-effort: a broker that is unreachable at boot must not crash engine
     * startup (the same promise {@code EngineNetworkLifecycle}'s javadoc already makes for
     * {@code RedisClient.connect()}) -- a failure here only means the original,
     * already-documented "first send may be dropped" risk stays exactly as it was, not that
     * anything gets worse.
     *
     * <p>{@code partitionsFor(topic)} itself is bounded by {@code max.block.ms=0}, same as
     * {@code send()} -- one call cannot wait for metadata. But the producer's background I/O
     * thread keeps refreshing metadata regardless of {@code max.block.ms} (that setting only
     * bounds how long a FOREGROUND call waits), so retrying this cheap call for up to {@link
     * #METADATA_WARMUP_TIMEOUT} lets that background fetch land before returning -- at which
     * point a real {@code send()} finds metadata already cached instead of failing.
     */
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
        // Docker verification (2026-09-07) found a failure mode GameEventPublisher's synchronous
        // try/catch around send() cannot see: with max.block.ms=0, a send whose topic metadata
        // isn't cached yet (e.g. the very first event ever published to a fresh topic) can fail
        // ASYNCHRONOUSLY -- the call to send() itself returns normally, and the failure only
        // surfaces via the returned Future/this callback. Without a callback that Future is never
        // read, so the event silently vanishes with no log line anywhere. This callback closes
        // that gap; it does not change the accepted "Kafka events are not the durability story"
        // trade-off (§9.3) -- it only makes an unexpected failure observable instead of silent,
        // matching how DistributedRoomSnapshotStore/GameEventPublisher already log every failure they see.
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
