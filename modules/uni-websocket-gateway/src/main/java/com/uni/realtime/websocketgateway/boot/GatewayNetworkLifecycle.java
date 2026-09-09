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
