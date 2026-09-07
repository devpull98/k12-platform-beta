package com.uni.realtime.gateway.routing;

import com.uni.realtime.gateway.metrics.GatewayMetrics;
import com.uni.realtime.gateway.net.BackpressureHandler;
import com.uni.realtime.protocol.GameMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Gateway's side of the internal frame channel (ADR-001, PH-2, §4.5, §8.2): one long-lived
 * connection per known Engine pod, shared by every room -- never one connection per room.
 *
 * <p>Routing is entirely learned, never computed: a room with no cache entry goes out
 * round-robin, and whichever pod answers stamps {@code InternalHeader.owner_pod_id} on its
 * response, which is what {@link RouteCache} learns from. This class does not, and must not,
 * know anything about {@code room_id % N} -- that rule lives only in Engine's
 * {@code RoomOwnership} (Task 10); the day it changes to Cluster Sharding, nothing here needs
 * to change.
 */
public final class FrameChannelClient implements EngineSender {

    private final RouteCache routeCache;
    private final Consumer<GameMessage> onResponse;
    private final Consumer<Set<String>> onPodDisconnected;
    private final EventLoopGroup eventLoopGroup;
    private final GatewayMetrics gatewayMetrics;

    private final Map<String, Channel> podChannels = new ConcurrentHashMap<>();
    private final List<String> knownPods = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinCursor = new AtomicInteger();

    /**
     * @param onPodDisconnected receives the room ids {@link RouteCache#evictPod} just orphaned
     *     (§9.7, Task 13) -- whoever is still connected to those rooms lost their route and needs
     *     {@code CONNECTION_DEGRADED} without their WebSocket being closed. Empty when the pod
     *     had no rooms cached yet (nothing to degrade).
     */
    public FrameChannelClient(RouteCache routeCache, Consumer<GameMessage> onResponse,
            Consumer<Set<String>> onPodDisconnected, EventLoopGroup eventLoopGroup, GatewayMetrics gatewayMetrics) {
        this.routeCache = routeCache;
        this.onResponse = onResponse;
        this.onPodDisconnected = onPodDisconnected;
        this.eventLoopGroup = eventLoopGroup;
        this.gatewayMetrics = gatewayMetrics;
    }

    /** Opens (and keeps open) the one connection this pair of pods will ever need. */
    public void connect(String podId, String host, int port) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, BackpressureHandler.WATER_MARK)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // §10.2 / plan.md Task 9: the same backpressure primitive on this hop
                        // too -- if Engine can't keep up reading responses off this connection,
                        // this channel backs up and stops accepting more requests to forward.
                        ch.pipeline().addLast(new BackpressureHandler(gatewayMetrics));
                        for (var handler : InternalFrameCodec.newHandlers()) {
                            ch.pipeline().addLast(handler);
                        }
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<GameMessage>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, GameMessage message) {
                                handleResponse(message);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                handleDisconnect(podId);
                            }
                        });
                    }
                });

        Channel channel = bootstrap.connect(host, port).sync().channel();
        podChannels.put(podId, channel);
        knownPods.add(podId);
    }

    /**
     * Routes by {@code room_id} when the cache already knows the owner; otherwise
     * round-robins across every connected pod (§4.5 step 2) and lets the response teach the
     * cache the real answer.
     */
    public void send(GameMessage message) {
        String targetPod = routeCache.lookup(message.getRoomId())
                .filter(podChannels::containsKey)
                .orElseGet(this::pickRoundRobin);

        podChannels.get(targetPod).writeAndFlush(message);
    }

    private String pickRoundRobin() {
        List<String> pods = knownPods;
        if (pods.isEmpty()) {
            throw new IllegalStateException("no engine pod connections available");
        }
        int index = Math.floorMod(roundRobinCursor.getAndIncrement(), pods.size());
        return pods.get(index);
    }

    private void handleResponse(GameMessage message) {
        String ownerPodId = message.getInternal().getOwnerPodId();
        if (!ownerPodId.isEmpty()) {
            routeCache.learn(message.getRoomId(), ownerPodId);
        }
        onResponse.accept(message);
    }

    /** A pod's connection dropped -- stop sending to it and stop trusting cache entries for it. */
    private void handleDisconnect(String podId) {
        podChannels.remove(podId);
        knownPods.remove(podId);
        Set<String> affectedRoomIds = routeCache.evictPod(podId);
        if (!affectedRoomIds.isEmpty()) {
            onPodDisconnected.accept(affectedRoomIds);
        }
    }
}
