package com.uni.realtime.engine.boot;

import com.uni.realtime.engine.events.GameEventPublisher;
import com.uni.realtime.engine.metrics.EngineMetrics;
import com.uni.realtime.engine.net.FrameChannelServer;
import com.uni.realtime.engine.persistence.KafkaGameEventSink;
import com.uni.realtime.engine.persistence.RedisRoomLeaseStore;
import com.uni.realtime.engine.persistence.RedisSnapshotStore;
import com.uni.realtime.engine.room.ModuloRoomOwnership;
import com.uni.realtime.engine.room.NoopRoomSnapshotStore;
import com.uni.realtime.engine.room.RedisLeaseRoomOwnership;
import com.uni.realtime.engine.room.RoomOwnership;
import com.uni.realtime.engine.room.RoomSnapshotStore;
import com.uni.realtime.engine.room.RoomSupervisor;
import com.uni.realtime.engine.scoring.FormulaScoreCalculator;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.pekko.actor.typed.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Task 13: actually starts the internal frame channel and the actor system on process boot --
 * before this, {@code EngineApplication} booted Spring and nothing else (its own javadoc said
 * so explicitly: "neither is started yet"). Unlike the Gateway side (see {@code
 * GatewayNetworkLifecycle}), nothing here waits on an external decision -- {@link RoomOwnership}
 * needs only this pod's own configuration, not a signed-ticket format from another team.
 *
 * <p>Task 14 (2026-09-07): {@code uni.engine.redis.enabled} (default {@code false}) switches
 * between {@link ModuloRoomOwnership} with no snapshotting (today's behavior, unchanged) and
 * {@link RedisLeaseRoomOwnership} + {@link RedisSnapshotStore} (the fix for "scale/crash breaks
 * the room_id % N hash", system-architecture.md §9.2 Rủi ro 4). Default OFF because neither
 * {@link RedisRoomLeaseStore} nor {@link RedisSnapshotStore} has run against a real Redis
 * instance -- see their javadoc. Flip it only after that verification (plan.md Task 14's
 * remaining "chưa làm"), never as a side effect of this class compiling.
 *
 * <p>Task 18 (2026-09-07): {@code uni.engine.kafka.enabled} (default {@code false}, same
 * reasoning as {@code redis.enabled} -- no broker in this development environment) wires a
 * {@link GameEventPublisher} backed by {@link KafkaGameEventSink} and forwards it to every
 * {@code RoomActor} {@link RoomSupervisor} spawns, which ships each accepted
 * {@code SubmitAnswer}'s {@code AnswerAck}, partitioned by {@code room_id} (a documented stand-in
 * for {@code session_id} -- see {@code RoomActor.publishGameEvent}'s javadoc).
 */
@Component
public final class EngineNetworkLifecycle implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EngineNetworkLifecycle.class);

    private final EngineMetrics engineMetrics;
    private final String podId;
    private final int podCount;
    private final int framePort;
    private final boolean redisEnabled;
    private final String redisUri;
    private final long leaseTtlSeconds;
    private final boolean kafkaEnabled;
    private final String kafkaBootstrapServers;
    private final String kafkaTopic;
    private final int kafkaQueueCapacity;

    private ActorSystem<RoomSupervisor.Command> system;
    private FrameChannelServer frameChannelServer;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> leaseConnection;
    private StatefulRedisConnection<String, byte[]> snapshotConnection;
    private ScheduledExecutorService leaseRenewalScheduler;
    private GameEventPublisher gameEventPublisher;

    public EngineNetworkLifecycle(EngineMetrics engineMetrics,
            @Value("${uni.engine.pod-id}") String podId,
            @Value("${uni.engine.pod-count}") int podCount,
            @Value("${uni.engine.frame-port}") int framePort,
            @Value("${uni.engine.redis.enabled}") boolean redisEnabled,
            @Value("${uni.engine.redis.uri}") String redisUri,
            @Value("${uni.engine.redis.lease-ttl-seconds}") long leaseTtlSeconds,
            @Value("${uni.engine.kafka.enabled}") boolean kafkaEnabled,
            @Value("${uni.engine.kafka.bootstrap-servers}") String kafkaBootstrapServers,
            @Value("${uni.engine.kafka.topic}") String kafkaTopic,
            @Value("${uni.engine.kafka.queue-capacity}") int kafkaQueueCapacity) {
        this.engineMetrics = engineMetrics;
        this.podId = podId;
        this.podCount = podCount;
        this.framePort = framePort;
        this.redisEnabled = redisEnabled;
        this.redisUri = redisUri;
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.kafkaEnabled = kafkaEnabled;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.kafkaTopic = kafkaTopic;
        this.kafkaQueueCapacity = kafkaQueueCapacity;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // §7.5/decision B2: room_id % pod-count, isolated behind RoomOwnership. Pod naming
        // ("engine-0".."engine-{pod-count-1}") matches application.yml's ENGINE_POD_ID default.
        // Kept even when Redis is enabled: it is RedisLeaseRoomOwnership's fallback for when
        // the lease store is unreachable (Task 14 AC).
        List<String> podIds = IntStream.range(0, podCount).mapToObj(i -> "engine-" + i).toList();
        ModuloRoomOwnership modulo = new ModuloRoomOwnership(podId, podIds);

        RoomOwnership roomOwnership = modulo;
        RoomSnapshotStore snapshotStore = NoopRoomSnapshotStore.INSTANCE;

        if (redisEnabled) {
            redisClient = RedisClient.create(redisUri);
            leaseConnection = redisClient.connect();
            snapshotConnection = redisClient.connect(RedisSnapshotStore.CODEC);

            RedisRoomLeaseStore leaseStore = new RedisRoomLeaseStore(leaseConnection.async());
            RedisLeaseRoomOwnership leaseRoomOwnership = new RedisLeaseRoomOwnership(
                    podId, leaseStore, Duration.ofSeconds(leaseTtlSeconds), modulo);
            roomOwnership = leaseRoomOwnership;
            snapshotStore = new RedisSnapshotStore(snapshotConnection.async());

            long renewalIntervalSeconds = Math.max(1, leaseTtlSeconds / 3);
            leaseRenewalScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "engine-lease-renewal");
                thread.setDaemon(true);
                return thread;
            });
            leaseRenewalScheduler.scheduleAtFixedRate(leaseRoomOwnership::renewAll,
                    renewalIntervalSeconds, renewalIntervalSeconds, TimeUnit.SECONDS);

            log.info("pod {}: RedisLeaseRoomOwnership + Hot Snapshot ENABLED (ttl={}s, uri={}) -- "
                            + "NOT verified against a real Redis instance in development, see "
                            + "RedisRoomLeaseStore/RedisSnapshotStore javadoc before trusting this in staging",
                    podId, leaseTtlSeconds, redisUri);
        } else {
            log.info("pod {}: uni.engine.redis.enabled=false -- ModuloRoomOwnership, no Hot Snapshot (Phase 1 default)", podId);
        }

        if (kafkaEnabled) {
            gameEventPublisher = new GameEventPublisher(
                    new KafkaGameEventSink(kafkaBootstrapServers, kafkaTopic), kafkaQueueCapacity);
            gameEventPublisher.start();
            log.info("pod {}: Kafka event publishing ENABLED (topic={}, bootstrap-servers={}) -- "
                            + "NOT verified against a real Kafka broker in development, see KafkaGameEventSink javadoc",
                    podId, kafkaTopic, kafkaBootstrapServers);
        } else {
            log.info("pod {}: uni.engine.kafka.enabled=false -- SubmitAnswer outcomes are not published (Phase 1 default)", podId);
        }

        system = ActorSystem.create(
                RoomSupervisor.create(roomOwnership, FormulaScoreCalculator.binaryChoice(), engineMetrics,
                        Clock.systemUTC(), snapshotStore, gameEventPublisher),
                "engine");

        frameChannelServer = new FrameChannelServer(framePort, roomOwnership,
                (channel, message) -> system.tell(new RoomSupervisor.Dispatch(message, channel)),
                channel -> system.tell(new RoomSupervisor.ChannelClosed(channel)),
                engineMetrics);
        frameChannelServer.start();
    }

    @Override
    public void destroy() throws Exception {
        if (gameEventPublisher != null) {
            gameEventPublisher.close();
        }
        if (leaseRenewalScheduler != null) {
            leaseRenewalScheduler.shutdownNow();
        }
        if (frameChannelServer != null) {
            frameChannelServer.shutdown();
        }
        if (system != null) {
            system.terminate();
        }
        if (leaseConnection != null) {
            leaseConnection.close();
        }
        if (snapshotConnection != null) {
            snapshotConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }
}
