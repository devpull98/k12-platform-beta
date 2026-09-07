package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.auth.TicketVerifier;
import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.gateway.metrics.GatewayMetrics;
import com.uni.realtime.gateway.routing.EngineSender;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/**
 * The WebSocket edge (ADR-005): Spring Boot only boots the process and serves
 * {@code /actuator/*}, never this. §13.2: one fixed event loop group sized {@code cores * 2}
 * handles every connection -- no per-request thread, no blocking call is ever allowed to run
 * on it.
 */
public final class GatewayBootstrap {

    private final int port;
    private final TicketVerifier ticketVerifier;
    private final RoomRegistry roomRegistry;
    private final GatewayMetrics gatewayMetrics;
    private final IpAdmissionController ipAdmissionController;
    private final EngineSender engineSender;

    private EventLoopGroup eventLoopGroup;
    private Channel serverChannel;

    /**
     * {@code roomRegistry} is shared across every connection this bootstrap accepts (Task 8)
     * -- unlike {@code TicketAuthHandler}/{@code RateLimitHandler}, which {@link GatewayPipeline}
     * builds fresh per channel, the registry is exactly this pod's one {@code room_id ->
     * Set<Channel>} map and must be the same instance a {@code Broadcaster} fans out through.
     * {@code gatewayMetrics} is likewise one shared instance (Task 12) so its metrics are
     * registered once, eagerly, rather than re-registered per connection. {@code
     * ipAdmissionController} (Task 7, §5.6 L1) is shared for the same reason: it counts
     * handshake attempts per IP across every connection, not just one. {@code engineSender}
     * (Task 13) is the pod's one {@code FrameChannelClient} -- every connection's {@code
     * RoomRouteHandler} forwards through the same set of Engine-pod connections, never one each.
     */
    public GatewayBootstrap(int port, TicketVerifier ticketVerifier, RoomRegistry roomRegistry,
            GatewayMetrics gatewayMetrics, IpAdmissionController ipAdmissionController, EngineSender engineSender) {
        this.port = port;
        this.ticketVerifier = ticketVerifier;
        this.roomRegistry = roomRegistry;
        this.gatewayMetrics = gatewayMetrics;
        this.ipAdmissionController = ipAdmissionController;
        this.engineSender = engineSender;
    }

    public void start() throws InterruptedException {
        eventLoopGroup = new MultiThreadIoEventLoopGroup(
                Runtime.getRuntime().availableProcessors() * 2, NioIoHandler.newFactory());

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(eventLoopGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, BackpressureHandler.WATER_MARK)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        GatewayPipeline.addTo(ch.pipeline(), ticketVerifier, roomRegistry, gatewayMetrics, ipAdmissionController, engineSender);
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
    }

    /** Actual bound port -- useful when constructed with port 0 (ephemeral, tests only). */
    public int boundPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void shutdown() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().sync();
        }
    }
}
