package com.uni.realtime.engine.net;

import com.uni.realtime.engine.room.RoomOwnership;
import com.uni.realtime.protocol.GameMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.util.function.Consumer;

/**
 * Engine-side listener for the internal frame channel (ADR-001): Gateway pods dial in and
 * keep one long-lived TCP connection open, carrying every room they have a client for. This
 * class bootstraps Netty, applies {@link FrameCodec}, then hands every decoded
 * {@link GameMessage} to {@link RoomOwnershipHandler} (Task 10) to decide whether it belongs
 * to this pod.
 */
public final class FrameChannelServer {

    /** plan.md Task 9 AC: 32 KB low / 64 KB high, per channel -- same value as the Gateway side. */
    static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(32 * 1024, 64 * 1024);

    private final int port;
    private final RoomOwnership roomOwnership;
    private final Consumer<GameMessage> onOwnedMessage;
    private final MeterRegistry meterRegistry;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public FrameChannelServer(int port, RoomOwnership roomOwnership, Consumer<GameMessage> onOwnedMessage,
            MeterRegistry meterRegistry) {
        this.port = port;
        this.roomOwnership = roomOwnership;
        this.onOwnedMessage = onOwnedMessage;
        this.meterRegistry = meterRegistry;
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
                        // §10.2 / plan.md Task 9: if this pod can't keep up writing responses
                        // (or NOT_OWNER replies) fast enough, this connection backs up and
                        // stops reading more requests off it -- the same mechanism as every
                        // other hop, applied here instead of a bespoke mailbox-depth signal.
                        ch.pipeline().addLast(newBackpressureHandler(meterRegistry));
                        for (ChannelHandler handler : FrameCodec.newHandlers()) {
                            ch.pipeline().addLast(handler);
                        }
                        ch.pipeline().addLast(new RoomOwnershipHandler(roomOwnership, onOwnedMessage));
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
    static ChannelHandler newBackpressureHandler(MeterRegistry meterRegistry) {
        return new BackpressureHandler(Counter.builder("channel_not_writable_total").register(meterRegistry));
    }

    private static final class BackpressureHandler extends ChannelInboundHandlerAdapter {
        private final Counter notWritableCounter;

        BackpressureHandler(Counter notWritableCounter) {
            this.notWritableCounter = notWritableCounter;
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            boolean writable = ctx.channel().isWritable();
            ctx.channel().config().setAutoRead(writable);
            if (!writable) {
                notWritableCounter.increment();
            }
            ctx.fireChannelWritabilityChanged();
        }
    }
}
