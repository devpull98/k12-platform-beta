package com.uni.realtime.websocketgateway.boot;

import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import com.uni.realtime.websocketgateway.fanout.Broadcaster;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.net.EngineResponseRouter;
import com.uni.realtime.websocketgateway.net.GatewayBootstrap;
import com.uni.realtime.websocketgateway.net.IpAdmissionController;
import com.uni.realtime.websocketgateway.net.StudentHandshakeAdmissionController;
import com.uni.realtime.websocketgateway.routing.EnginePodDiscovery;
import com.uni.realtime.websocketgateway.routing.FrameChannelClient;
import com.uni.realtime.websocketgateway.routing.RouteCache;
import com.uni.realtime.websocketgateway.routing.ValkeyEnginePodResolver;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Task 13: actually starts the WebSocket edge on process boot.
 *
 * <p>Gated on a real {@link JoinTokenVerifier} bean existing -- there isn't one yet (G1a/G1c,
 * tech-design.md §9.1, still waiting on the platform team for the signing algorithm and clock
 * skew tolerance). {@code JoinTokenAuthHandler}'s own javadoc already forbids wiring a temporary
 * verifier into staging/production, so the correct behavior with no real bean present is for
 * this component to simply not exist -- the app still boots and serves {@code /actuator/*}, it
 * just never opens the game WebSocket. Once G1a/G1c resolve, adding one {@code @Bean
 * JoinTokenVerifier} is the only change needed to light this up; nothing here changes.
 *
 * <p>{@code uni.gateway.engine.pods} entries are assigned pod ids by list position
 * ("engine-0", "engine-1", ...) -- a Phase 1 simplification, since the config today is a flat
 * host:port list with no id of its own. This requires each Engine pod's own {@code
 * uni.engine.pod-id} to match its position in this list; PH-1 / real service discovery should
 * replace this once it exists.
 *
 * <p>Task 21 (2026-09-09): the list above is still read exactly once here, at boot -- that part
 * is unchanged and, on its own, is the whole reason Gateway used to be unable to reach an Engine
 * pod started later (system-architecture.md §9.2 Rủi ro 4). When {@code
 * uni.gateway.engine.pod-discovery.enabled}, {@link EnginePodDiscovery} is additionally started
 * on its own background thread, polling {@link ValkeyEnginePodResolver} and dialing any pod not
 * already connected -- see that class's javadoc for why Valkey and not a Kubernetes-native
 * mechanism.
 */
@Component
@ConditionalOnBean(JoinTokenVerifier.class)
public final class GatewayNetworkLifecycle implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GatewayNetworkLifecycle.class);

    private final JoinTokenVerifier joinTokenVerifier;
    private final GatewayMetrics gatewayMetrics;
    private final int wsPort;
    private final List<String> enginePods;
    private final boolean podDiscoveryEnabled;
    private final String podDiscoveryRoomStoreUri;
    private final long podDiscoveryPollIntervalSeconds;

    private EventLoopGroup engineClientGroup;
    private FrameChannelClient frameChannelClient;
    private GatewayBootstrap gatewayBootstrap;
    private RedisClient podDiscoveryRoomStoreClient;
    private StatefulRedisConnection<String, String> podDiscoveryConnection;
    private EnginePodDiscovery enginePodDiscovery;

    public GatewayNetworkLifecycle(JoinTokenVerifier joinTokenVerifier, GatewayMetrics gatewayMetrics,
            @Value("${uni.gateway.ws-port}") int wsPort,
            @Value("#{'${uni.gateway.engine.pods}'.split(',')}") List<String> enginePods,
            @Value("${uni.gateway.engine.pod-discovery.enabled}") boolean podDiscoveryEnabled,
            @Value("${uni.gateway.engine.pod-discovery.room-store-uri}") String podDiscoveryRoomStoreUri,
            @Value("${uni.gateway.engine.pod-discovery.poll-interval-seconds}") long podDiscoveryPollIntervalSeconds) {
        this.joinTokenVerifier = joinTokenVerifier;
        this.gatewayMetrics = gatewayMetrics;
        this.wsPort = wsPort;
        this.enginePods = enginePods;
        this.podDiscoveryEnabled = podDiscoveryEnabled;
        this.podDiscoveryRoomStoreUri = podDiscoveryRoomStoreUri;
        this.podDiscoveryPollIntervalSeconds = podDiscoveryPollIntervalSeconds;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        RoomRegistry roomRegistry = new RoomRegistry();
        Broadcaster broadcaster = new Broadcaster(roomRegistry, gatewayMetrics);
        EngineResponseRouter responseRouter = new EngineResponseRouter(roomRegistry, broadcaster);
        RouteCache routeCache = new RouteCache();

        engineClientGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        frameChannelClient = new FrameChannelClient(routeCache, responseRouter::route,
                responseRouter::broadcastConnectionDegraded, engineClientGroup, gatewayMetrics);
        for (int i = 0; i < enginePods.size(); i++) {
            String[] hostPort = enginePods.get(i).trim().split(":");
            frameChannelClient.connect("engine-" + i, hostPort[0], Integer.parseInt(hostPort[1]));
        }

        if (podDiscoveryEnabled) {
            // Task 21: separate connection from Engine's own room-store client (different
            // process), same store instance. Off the Netty EventLoop entirely -- both this
            // connect and every subsequent poll run on EnginePodDiscovery's own thread.
            podDiscoveryRoomStoreClient = RedisClient.create(podDiscoveryRoomStoreUri);
            podDiscoveryConnection = podDiscoveryRoomStoreClient.connect();
            ValkeyEnginePodResolver resolver = new ValkeyEnginePodResolver(podDiscoveryConnection.sync());
            enginePodDiscovery = new EnginePodDiscovery(
                    resolver, frameChannelClient, Duration.ofSeconds(podDiscoveryPollIntervalSeconds));
            enginePodDiscovery.start();
            log.info("engine pod discovery ENABLED (poll={}s, room-store-uri={}) -- Gateway can now "
                            + "reach an Engine pod started after this boot",
                    podDiscoveryPollIntervalSeconds, podDiscoveryRoomStoreUri);
        } else {
            log.info("uni.gateway.engine.pod-discovery.enabled=false -- only the static "
                    + "uni.gateway.engine.pods list is dialed (Phase 1 default)");
        }

        gatewayBootstrap = new GatewayBootstrap(wsPort, joinTokenVerifier, roomRegistry, gatewayMetrics,
                new IpAdmissionController(), new StudentHandshakeAdmissionController(), frameChannelClient);
        gatewayBootstrap.start();
    }

    @Override
    public void destroy() throws Exception {
        if (gatewayBootstrap != null) {
            gatewayBootstrap.shutdown();
        }
        if (engineClientGroup != null) {
            engineClientGroup.shutdownGracefully().sync();
        }
        if (enginePodDiscovery != null) {
            enginePodDiscovery.stop();
        }
        if (podDiscoveryConnection != null) {
            podDiscoveryConnection.close();
        }
        if (podDiscoveryRoomStoreClient != null) {
            podDiscoveryRoomStoreClient.shutdown();
        }
    }
}
