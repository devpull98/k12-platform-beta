package com.uni.realtime.gameengine.boot;

import com.uni.realtime.gameengine.events.GameEventPublisher;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.net.FrameChannelServer;
import com.uni.realtime.gameengine.persistence.KafkaGameEventSink;
import com.uni.realtime.gameengine.persistence.DistributedRoomLeaseStore;
import com.uni.realtime.gameengine.persistence.DistributedRoomSnapshotStore;
import com.uni.realtime.gameengine.persistence.EnginePodPresence;
import com.uni.realtime.gameengine.room.ModuloRoomOwnership;
import com.uni.realtime.gameengine.room.NoopRoomSnapshotStore;
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
 * needs only this pod's own configuration, not a signed-join-token format from another team.
 *
 * <p>Task 14 (2026-09-07): {@code uni.engine.room-store.enabled} (default {@code false})
 * switches between {@link ModuloRoomOwnership} with no snapshotting (today's behavior,
 * unchanged) and {@link LeaseBasedRoomOwnership} + {@link DistributedRoomSnapshotStore} (the
 * fix for "scale/crash breaks the room_id % N hash", system-architecture.md §9.2 Rủi ro 4).
 * Default OFF because neither {@link DistributedRoomLeaseStore} nor
 * {@link DistributedRoomSnapshotStore} has run against a real store instance -- see their
 * javadoc. Flip it only after that verification (plan.md Task 14's remaining "chưa làm"), never
 * as a side effect of this class compiling. "room-store" deliberately doesn't name the backing
 * product (Valkey today) -- see {@link DistributedRoomLeaseStore}'s javadoc for why.
 *
 * <p>Task 18 (2026-09-07): {@code uni.engine.kafka.enabled} (default {@code false}, same
 * reasoning as {@code room-store.enabled} -- no broker in this development environment) wires a
 * {@link GameEventPublisher} backed by {@link KafkaGameEventSink} and forwards it to every
 * {@code RoomActor} {@link RoomSupervisor} spawns, which ships each accepted
 * {@code SubmitAnswer}'s {@code AnswerAck}, partitioned by {@code room_id} (a documented stand-in
 * for {@code session_id} -- see {@code RoomActor.publishGameEvent}'s javadoc).
 *
 * <p>Task 21 (2026-09-09): when {@code room-store.enabled}, this pod also announces itself into
 * the room-store via {@link EnginePodPresence} on the same renewal cadence as its lease
 * renewals -- {@code ValkeyEnginePodResolver} (uni-websocket-gateway) is the read side, letting a
 * running Gateway discover this pod even if it started after Gateway booted
 * (system-architecture.md §9.2 Rủi ro 4).
 */
@Component
public final class EngineNetworkLifecycle implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EngineNetworkLifecycle.class);

    private final EngineMetrics engineMetrics;
    private final String podId;
    private final int podCount;
    private final int framePort;
    private final boolean roomStoreEnabled;
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

    public EngineNetworkLifecycle(EngineMetrics engineMetrics,
            @Value("${uni.engine.pod-id}") String podId,
            @Value("${uni.engine.pod-count}") int podCount,
            @Value("${uni.engine.frame-port}") int framePort,
            @Value("${uni.engine.room-store.enabled}") boolean roomStoreEnabled,
            @Value("${uni.engine.room-store.uri}") String roomStoreUri,
            @Value("${uni.engine.room-store.lease-ttl-seconds}") long leaseTtlSeconds,
            @Value("${uni.engine.advertised-host}") String advertisedHost,
            @Value("${uni.engine.kafka.enabled}") boolean kafkaEnabled,
            @Value("${uni.engine.kafka.bootstrap-servers}") String kafkaBootstrapServers,
            @Value("${uni.engine.kafka.topic}") String kafkaTopic,
            @Value("${uni.engine.kafka.queue-capacity}") int kafkaQueueCapacity) {
        this.engineMetrics = engineMetrics;
        this.podId = podId;
        this.podCount = podCount;
        this.framePort = framePort;
        this.roomStoreEnabled = roomStoreEnabled;
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
        // §7.5/decision B2: room_id % pod-count, isolated behind RoomOwnership. Pod naming
        // ("engine-0".."engine-{pod-count-1}") matches application.yml's ENGINE_POD_ID default.
        // Kept even when the room store is enabled: it is LeaseBasedRoomOwnership's fallback for
        // when the lease store is unreachable (Task 14 AC).
        List<String> podIds = IntStream.range(0, podCount).mapToObj(i -> "engine-" + i).toList();
        ModuloRoomOwnership modulo = new ModuloRoomOwnership(podId, podIds);

        RoomOwnership roomOwnership = modulo;
        RoomSnapshotStore snapshotStore = NoopRoomSnapshotStore.INSTANCE;

        if (roomStoreEnabled) {
            roomStoreClient = RedisClient.create(roomStoreUri);
            leaseConnection = roomStoreClient.connect();
            snapshotConnection = roomStoreClient.connect(DistributedRoomSnapshotStore.CODEC);

            DistributedRoomLeaseStore leaseStore = new DistributedRoomLeaseStore(leaseConnection.async());
            LeaseBasedRoomOwnership leaseRoomOwnership = new LeaseBasedRoomOwnership(
                    podId, leaseStore, Duration.ofSeconds(leaseTtlSeconds), modulo);
            roomOwnership = leaseRoomOwnership;
            snapshotStore = new DistributedRoomSnapshotStore(snapshotConnection.async());

            long renewalIntervalSeconds = Math.max(1, leaseTtlSeconds / 3);
            leaseRenewalScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "engine-lease-renewal");
                thread.setDaemon(true);
                return thread;
            });
            leaseRenewalScheduler.scheduleAtFixedRate(leaseRoomOwnership::renewAll,
                    renewalIntervalSeconds, renewalIntervalSeconds, TimeUnit.SECONDS);

            // Task 21: same store, same connection, same renewal cadence as the lease above --
            // announce once immediately (a freshly started pod must not wait a full cycle before
            // Gateway can discover it), then keep re-announcing so the TTL never lapses under a
            // healthy pod.
            EnginePodPresence podPresence = new EnginePodPresence(leaseConnection.async(), podId,
                    advertisedHost + ":" + framePort, Duration.ofSeconds(leaseTtlSeconds));
            podPresence.announce();
            leaseRenewalScheduler.scheduleAtFixedRate(podPresence::announce,
                    renewalIntervalSeconds, renewalIntervalSeconds, TimeUnit.SECONDS);

            log.info("pod {}: LeaseBasedRoomOwnership + Hot Snapshot ENABLED (ttl={}s, uri={}) -- "
                            + "NOT verified against a real store instance in development, see "
                            + "DistributedRoomLeaseStore/DistributedRoomSnapshotStore javadoc before trusting this in staging",
                    podId, leaseTtlSeconds, roomStoreUri);
        } else {
            log.info("pod {}: uni.engine.room-store.enabled=false -- ModuloRoomOwnership, no Hot Snapshot (Phase 1 default)", podId);
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
        if (roomStoreClient != null) {
            roomStoreClient.shutdown();
        }
    }
}
