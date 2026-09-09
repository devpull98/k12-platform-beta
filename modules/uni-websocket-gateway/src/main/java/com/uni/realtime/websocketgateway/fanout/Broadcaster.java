package com.uni.realtime.websocketgateway.fanout;

import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.protocol.DeliveryClass;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.concurrent.TimeUnit;

public final class Broadcaster {

    private final RoomRegistry roomRegistry;
    private final GatewayMetrics gatewayMetrics;

    public Broadcaster(RoomRegistry roomRegistry, GatewayMetrics gatewayMetrics) {
        this.roomRegistry = roomRegistry;
        this.gatewayMetrics = gatewayMetrics;
    }

    public void broadcast(String roomId, ByteBuf frame, DeliveryClass deliveryClass) {
        long startNanos = System.nanoTime();
        try {
            for (Channel channel : roomRegistry.channelsIn(roomId)) {
                if (!channel.isActive()) {
                    continue;
                }
                if (!channel.isWritable()) {
                    if (deliveryClass == DeliveryClass.CRITICAL) {
                        channel.close();
                    }
                    continue;
                }
                channel.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
            }
        } finally {
            frame.release();
            gatewayMetrics.fanoutLatencyTimer().record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void sendToOne(Channel channel, ByteBuf frame, DeliveryClass deliveryClass) {
        if (!channel.isActive()) {
            frame.release();
            return;
        }
        if (!channel.isWritable()) {
            if (deliveryClass == DeliveryClass.CRITICAL) {
                channel.close();
            }
            frame.release();
            return;
        }
        channel.writeAndFlush(new BinaryWebSocketFrame(frame));
    }
}
