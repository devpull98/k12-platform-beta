package com.uni.realtime.gateway.routing;

import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.gateway.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-socket test for Task 5's actual deliverable: routing that is round-robin until
 * learned, learned from a real response, and self-healing when a pod's connection drops.
 * RouteCacheTest already proves the map logic in isolation; this proves FrameChannelClient
 * wires it correctly end to end, against real (fake) Engine pods on loopback sockets.
 */
class FrameChannelClientTest {

    @Test
    void should_roundRobinAcrossConnectedPods_when_roomNotYetKnown() throws Exception {
        FakeEnginePod podA = new FakeEnginePod("engine-a").start();
        FakeEnginePod podB = new FakeEnginePod("engine-b").start();
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            FrameChannelClient client = new FrameChannelClient(new RouteCache(), ignored -> {}, clientGroup, new GatewayMetrics(new SimpleMeterRegistry()));
            client.connect(podA.podId, "localhost", podA.port());
            client.connect(podB.podId, "localhost", podB.port());

            client.send(messageFor("room-1"));
            client.send(messageFor("room-2"));

            assertThat(podA.receive().getRoomId()).isEqualTo("room-1");
            assertThat(podB.receive().getRoomId()).isEqualTo("room-2");
        } finally {
            clientGroup.shutdownGracefully().sync();
            podA.stop();
            podB.stop();
        }
    }

    @Test
    void should_learnRouteFromResponse_and_sendDirectlyWithoutAdvancingRoundRobin() throws Exception {
        FakeEnginePod podA = new FakeEnginePod("engine-a").start();
        FakeEnginePod podB = new FakeEnginePod("engine-b").start();
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            RouteCache routeCache = new RouteCache();
            BlockingQueue<GameMessage> responses = new LinkedBlockingQueue<>();
            FrameChannelClient client = new FrameChannelClient(routeCache, responses::add, clientGroup, new GatewayMetrics(new SimpleMeterRegistry()));
            client.connect(podA.podId, "localhost", podA.port());
            client.connect(podB.podId, "localhost", podB.port());

            client.send(messageFor("room-1")); // unknown -> round-robin cursor 0 -> pod A
            podA.receive();
            assertThat(responses.poll(2, TimeUnit.SECONDS)).isNotNull(); // response learned room-1 -> A

            client.send(messageFor("room-1")); // now known -> straight to A, cursor untouched
            assertThat(podA.receive().getRoomId()).isEqualTo("room-1");
            assertThat(podB.received).isEmpty();

            client.send(messageFor("room-2")); // still unknown -> round-robin advances to pod B
            assertThat(podB.receive().getRoomId()).isEqualTo("room-2");
        } finally {
            clientGroup.shutdownGracefully().sync();
            podA.stop();
            podB.stop();
        }
    }

    @Test
    void should_evictLearnedRouteAndFallBackToRoundRobin_when_podConnectionLost() throws Exception {
        FakeEnginePod podA = new FakeEnginePod("engine-a").start();
        FakeEnginePod podB = new FakeEnginePod("engine-b").start();
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            RouteCache routeCache = new RouteCache();
            FrameChannelClient client = new FrameChannelClient(routeCache, ignored -> {}, clientGroup, new GatewayMetrics(new SimpleMeterRegistry()));
            client.connect(podA.podId, "localhost", podA.port());
            client.connect(podB.podId, "localhost", podB.port());

            client.send(messageFor("room-1")); // round-robin -> A
            podA.receive();
            awaitRouteLearned(routeCache, "room-1", "engine-a");

            podA.stop(); // drop the connection out from under the client
            awaitRouteEvicted(routeCache, "room-1");

            client.send(messageFor("room-1")); // must not still think A owns it
            assertThat(podB.receive().getRoomId()).isEqualTo("room-1");
        } finally {
            clientGroup.shutdownGracefully().sync();
            podB.stop();
        }
    }

    private static GameMessage messageFor(String roomId) {
        return GameMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .setRoomId(roomId)
                .build();
    }

    private static void awaitRouteLearned(RouteCache cache, String roomId, String podId) throws InterruptedException {
        for (int i = 0; i < 100 && !cache.lookup(roomId).equals(java.util.Optional.of(podId)); i++) {
            Thread.sleep(20);
        }
        assertThat(cache.lookup(roomId)).contains(podId);
    }

    private static void awaitRouteEvicted(RouteCache cache, String roomId) throws InterruptedException {
        for (int i = 0; i < 100 && cache.lookup(roomId).isPresent(); i++) {
            Thread.sleep(20);
        }
        assertThat(cache.lookup(roomId)).isEmpty();
    }

    /** A minimal stand-in Engine pod: decodes internal frames, echoes an owner-stamped ack. */
    private static final class FakeEnginePod {
        final String podId;
        final BlockingQueue<GameMessage> received = new LinkedBlockingQueue<>();

        private EventLoopGroup group;
        private Channel serverChannel;
        private volatile Channel activeChildChannel;

        FakeEnginePod(String podId) {
            this.podId = podId;
        }

        FakeEnginePod start() throws InterruptedException {
            group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            activeChildChannel = ch;
                            for (var handler : InternalFrameCodec.newHandlers()) {
                                ch.pipeline().addLast(handler);
                            }
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<GameMessage>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, GameMessage msg) {
                                    received.add(msg);
                                    ctx.writeAndFlush(GameMessage.newBuilder()
                                            .setType(MessageType.ANSWER_ACK)
                                            .setRoomId(msg.getRoomId())
                                            .setInternal(InternalHeader.newBuilder().setOwnerPodId(podId))
                                            .build());
                                }
                            });
                        }
                    });
            serverChannel = bootstrap.bind(0).sync().channel();
            return this;
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        GameMessage receive() throws InterruptedException {
            GameMessage message = received.poll(2, TimeUnit.SECONDS);
            assertThat(message).as("pod %s never received a message", podId).isNotNull();
            return message;
        }

        void stop() throws InterruptedException {
            if (activeChildChannel != null) {
                activeChildChannel.close().sync();
            }
            serverChannel.close().sync();
            group.shutdownGracefully().sync();
        }
    }
}
