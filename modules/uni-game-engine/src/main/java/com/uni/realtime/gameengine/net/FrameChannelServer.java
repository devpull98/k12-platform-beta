package com.uni.realtime.gameengine.net;

import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.room.RoomOwnership;
import com.uni.realtime.protocol.GameMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class FrameChannelServer {

    static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(32 * 1024, 64 * 1024);

    private final int port;
    private final RoomOwnership roomOwnership;
    private final BiConsumer<Channel, GameMessage> onOwnedMessage;
    private final Consumer<Channel> onChannelClosed;
    private final EngineMetrics engineMetrics;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public FrameChannelServer(int port, RoomOwnership roomOwnership, BiConsumer<Channel, GameMessage> onOwnedMessage,
            Consumer<Channel> onChannelClosed, EngineMetrics engineMetrics) {
        this.port = port;
        this.roomOwnership = roomOwnership;
        this.onOwnedMessage = onOwnedMessage;
        this.onChannelClosed = onChannelClosed;
        this.engineMetrics = engineMetrics;
    }

    public void start() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(newBackpressureHandler(engineMetrics));
                        for (ChannelHandler handler : FrameCodec.newHandlers()) {
                            ch.pipeline().addLast(handler);
                        }
                        ch.pipeline().addLast(new RoomOwnershipHandler(roomOwnership, message -> onOwnedMessage.accept(ch, message)));
                        ch.closeFuture().addListener(future -> onChannelClosed.accept(ch));
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

    /** Package-private so {@code BackpressureTest} can build an EmbeddedChannel around it directly. */
    static ChannelHandler newBackpressureHandler(EngineMetrics engineMetrics) {
        return new BackpressureHandler(engineMetrics);
    }

    private static final class BackpressureHandler extends ChannelInboundHandlerAdapter {
        private final EngineMetrics engineMetrics;

        BackpressureHandler(EngineMetrics engineMetrics) {
            this.engineMetrics = engineMetrics;
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            boolean writable = ctx.channel().isWritable();
            ctx.channel().config().setAutoRead(writable);
            if (!writable) {
                engineMetrics.recordChannelNotWritable();
            }
            ctx.fireChannelWritabilityChanged();
        }
    }
}
