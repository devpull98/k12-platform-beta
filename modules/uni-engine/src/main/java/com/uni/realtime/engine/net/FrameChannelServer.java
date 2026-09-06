package com.uni.realtime.engine.net;

import com.uni.realtime.protocol.GameMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.function.Consumer;

/**
 * Engine-side listener for the internal frame channel (ADR-001): Gateway pods dial in and
 * keep one long-lived TCP connection open, carrying every room they have a client for. This
 * class only bootstraps Netty and applies {@link FrameCodec}; where a decoded
 * {@link GameMessage} goes next (which {@code RoomActor} owns it) is {@code RoomOwnership}'s
 * job (Task 10), not this one's.
 */
public final class FrameChannelServer {

    private final int port;
    private final Consumer<GameMessage> onMessage;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public FrameChannelServer(int port, Consumer<GameMessage> onMessage) {
        this.port = port;
        this.onMessage = onMessage;
    }

    public void start() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        for (ChannelHandler handler : FrameCodec.newHandlers()) {
                            ch.pipeline().addLast(handler);
                        }
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<GameMessage>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, GameMessage msg) {
                                onMessage.accept(msg);
                            }
                        });
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
    }

    /** Actual bound port -- useful when constructed with port 0 (ephemeral, tests only). */
    public int boundPort() {
        return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void shutdown() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
    }
}
