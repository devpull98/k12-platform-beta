package com.uni.realtime.gateway.routing;

import com.uni.realtime.protocol.GameMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.List;
import java.util.Map;
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
public final class FrameChannelClient {

    private final RouteCache routeCache;
    private final Consumer<GameMessage> onResponse;
    private final EventLoopGroup eventLoopGroup;

    private final Map<String, Channel> podChannels = new ConcurrentHashMap<>();
    private final List<String> knownPods = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinCursor = new AtomicInteger();

    public FrameChannelClient(RouteCache routeCache, Consumer<GameMessage> onResponse, EventLoopGroup eventLoopGroup) {
        this.routeCache = routeCache;
        this.onResponse = onResponse;
        this.eventLoopGroup = eventLoopGroup;
    }

    /** Opens (and keeps open) the one connection this pair of pods will ever need. */
    public void connect(String podId, String host, int port) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
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
        routeCache.evictPod(podId);
    }
}
