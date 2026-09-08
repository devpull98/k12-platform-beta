package com.uni.realtime.websocketgateway.boot;

import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import com.uni.realtime.websocketgateway.fanout.Broadcaster;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.net.EngineResponseRouter;
import com.uni.realtime.websocketgateway.net.GatewayBootstrap;
import com.uni.realtime.websocketgateway.net.IpAdmissionController;
import com.uni.realtime.websocketgateway.net.StudentHandshakeAdmissionController;
import com.uni.realtime.websocketgateway.routing.FrameChannelClient;
import com.uni.realtime.websocketgateway.routing.RouteCache;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

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
 */
@Component
@ConditionalOnBean(JoinTokenVerifier.class)
public final class GatewayNetworkLifecycle implements ApplicationRunner, DisposableBean {

    private final JoinTokenVerifier joinTokenVerifier;
    private final GatewayMetrics gatewayMetrics;
    private final int wsPort;
    private final List<String> enginePods;

    private EventLoopGroup engineClientGroup;
    private FrameChannelClient frameChannelClient;
    private GatewayBootstrap gatewayBootstrap;

    public GatewayNetworkLifecycle(JoinTokenVerifier joinTokenVerifier, GatewayMetrics gatewayMetrics,
            @Value("${uni.gateway.ws-port}") int wsPort,
            @Value("#{'${uni.gateway.engine.pods}'.split(',')}") List<String> enginePods) {
        this.joinTokenVerifier = joinTokenVerifier;
        this.gatewayMetrics = gatewayMetrics;
        this.wsPort = wsPort;
        this.enginePods = enginePods;
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
    }
}
