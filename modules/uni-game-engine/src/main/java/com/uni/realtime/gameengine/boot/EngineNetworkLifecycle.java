package com.uni.realtime.gameengine.boot;

import com.uni.realtime.gameengine.events.GameEventPublisher;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.net.FrameChannelServer;
import com.uni.realtime.gameengine.persistence.KafkaGameEventSink;
import com.uni.realtime.gameengine.persistence.DistributedGameSessionDefinitionStore;
import com.uni.realtime.gameengine.persistence.DistributedRoomLeaseStore;
import com.uni.realtime.gameengine.persistence.DistributedRoomSnapshotStore;
import com.uni.realtime.gameengine.persistence.EnginePodPresence;
import com.uni.realtime.gameengine.room.GameSessionDefinitionStore;
import com.uni.realtime.gameengine.room.LeaseBasedRoomOwnership;
import com.uni.realtime.gameengine.room.RoomOwnership;
import com.uni.realtime.gameengine.room.RoomSnapshotStore;
import com.uni.realtime.gameengine.room.RoomSupervisor;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public final class EngineNetworkLifecycle implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EngineNetworkLifecycle.class);

    private final EngineMetrics engineMetrics;
    private final String podId;
    private final int framePort;
    private final String roomStoreUri;
    private final long leaseTtlSeconds;
    private final String advertisedHost;
    private final boolean kafkaEnabled;
    private final String kafkaBootstrapServers;
    private final String kafkaTopic;
    private final int kafkaQueueCapacity;

    private ActorSystem<RoomSupervisor.Command> system;
    private FrameChannelServer frameChannelServer;
    private RedisClient roomStoreClient;
    private StatefulRedisConnection<String, String> leaseConnection;
    private StatefulRedisConnection<String, byte[]> snapshotConnection;
    private ScheduledExecutorService leaseRenewalScheduler;
    private GameEventPublisher gameEventPublisher;
    private volatile GameSessionDefinitionStore gameSessionDefinitionStore;

    /** Null until {@link #run} has wired the room-store connection -- {@code
     * GameSessionProvisioningController} must treat null as "engine still starting up" (503), not
     * NPE, since Tomcat can start accepting requests before this {@code ApplicationRunner} completes. */
    public GameSessionDefinitionStore gameSessionDefinitionStore() {
        return gameSessionDefinitionStore;
    }

    public EngineNetworkLifecycle(EngineMetrics engineMetrics,
            @Value("${uni.engine.pod-id}") String podId,
            @Value("${uni.engine.frame-port}") int framePort,
            @Value("${uni.engine.room-store.uri}") String roomStoreUri,
            @Value("${uni.engine.room-store.lease-ttl-seconds}") long leaseTtlSeconds,
            @Value("${uni.engine.advertised-host}") String advertisedHost,
            @Value("${uni.engine.kafka.enabled}") boolean kafkaEnabled,
            @Value("${uni.engine.kafka.bootstrap-servers}") String kafkaBootstrapServers,
            @Value("${uni.engine.kafka.topic}") String kafkaTopic,
            @Value("${uni.engine.kafka.queue-capacity}") int kafkaQueueCapacity) {
        this.engineMetrics = engineMetrics;
        this.podId = podId;
        this.framePort = framePort;
        this.roomStoreUri = roomStoreUri;
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.advertisedHost = advertisedHost;
        this.kafkaEnabled = kafkaEnabled;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.kafkaTopic = kafkaTopic;
        this.kafkaQueueCapacity = kafkaQueueCapacity;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        roomStoreClient = RedisClient.create(roomStoreUri);
        leaseConnection = roomStoreClient.connect();
        snapshotConnection = roomStoreClient.connect(DistributedRoomSnapshotStore.CODEC);

        DistributedRoomLeaseStore leaseStore = new DistributedRoomLeaseStore(leaseConnection.async());
        LeaseBasedRoomOwnership leaseRoomOwnership = new LeaseBasedRoomOwnership(
                podId, leaseStore, Duration.ofSeconds(leaseTtlSeconds));
        RoomOwnership roomOwnership = leaseRoomOwnership;
        RoomSnapshotStore snapshotStore = new DistributedRoomSnapshotStore(snapshotConnection.async());
        // Reuses the SAME connection/codec as snapshotStore above -- no second Valkey connection,
        // same principle as EnginePodPresence reusing the lease connection (Task 21).
        gameSessionDefinitionStore = new DistributedGameSessionDefinitionStore(snapshotConnection.async());

        long renewalIntervalSeconds = Math.max(1, leaseTtlSeconds / 3);
        leaseRenewalScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "engine-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
        leaseRenewalScheduler.scheduleAtFixedRate(leaseRoomOwnership::renewAll,
                renewalIntervalSeconds, renewalIntervalSeconds, TimeUnit.SECONDS);

        EnginePodPresence podPresence = new EnginePodPresence(leaseConnection.async(), podId,
                advertisedHost + ":" + framePort, Duration.ofSeconds(leaseTtlSeconds));
        podPresence.announce();
        leaseRenewalScheduler.scheduleAtFixedRate(podPresence::announce,
                renewalIntervalSeconds, renewalIntervalSeconds, TimeUnit.SECONDS);

        log.info("pod {}: LeaseBasedRoomOwnership + Hot Snapshot (ttl={}s, uri={}) -- room_id % N is "
                        + "gone, room-store is now a hard dependency; NOT verified against a real store "
                        + "instance in staging, see DistributedRoomLeaseStore/DistributedRoomSnapshotStore "
                        + "javadoc before trusting this in production",
                podId, leaseTtlSeconds, roomStoreUri);

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
                        Clock.systemUTC(), snapshotStore, gameEventPublisher, gameSessionDefinitionStore),
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
        if (roomStoreClient != null) {
            roomStoreClient.shutdown();
        }
    }
}
