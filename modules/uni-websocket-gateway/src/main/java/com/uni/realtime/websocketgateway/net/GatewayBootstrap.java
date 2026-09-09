package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.routing.EngineSender;
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

public final class GatewayBootstrap {

    private final int port;
    private final JoinTokenVerifier joinTokenVerifier;
    private final RoomRegistry roomRegistry;
    private final GatewayMetrics gatewayMetrics;
    private final IpAdmissionController ipAdmissionController;
    private final StudentHandshakeAdmissionController studentHandshakeAdmission;
    private final EngineSender engineSender;

    private EventLoopGroup eventLoopGroup;
    private Channel serverChannel;

    public GatewayBootstrap(int port, JoinTokenVerifier joinTokenVerifier, RoomRegistry roomRegistry,
            GatewayMetrics gatewayMetrics, IpAdmissionController ipAdmissionController,
            StudentHandshakeAdmissionController studentHandshakeAdmission, EngineSender engineSender) {
        this.port = port;
        this.joinTokenVerifier = joinTokenVerifier;
        this.roomRegistry = roomRegistry;
        this.gatewayMetrics = gatewayMetrics;
        this.ipAdmissionController = ipAdmissionController;
        this.studentHandshakeAdmission = studentHandshakeAdmission;
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
                        GatewayPipeline.addTo(ch.pipeline(), joinTokenVerifier, roomRegistry, gatewayMetrics,
                                ipAdmissionController, studentHandshakeAdmission, engineSender);
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
    }

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
