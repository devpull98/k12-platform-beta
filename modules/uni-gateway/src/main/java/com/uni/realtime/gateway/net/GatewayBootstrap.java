package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.auth.TicketVerifier;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
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

    private EventLoopGroup eventLoopGroup;
    private Channel serverChannel;

    public GatewayBootstrap(int port, TicketVerifier ticketVerifier) {
        this.port = port;
        this.ticketVerifier = ticketVerifier;
    }

    public void start() throws InterruptedException {
        eventLoopGroup = new MultiThreadIoEventLoopGroup(
                Runtime.getRuntime().availableProcessors() * 2, NioIoHandler.newFactory());

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(eventLoopGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        GatewayPipeline.addTo(ch.pipeline(), ticketVerifier);
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
