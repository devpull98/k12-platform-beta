package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.WriteBufferWaterMark;

public final class BackpressureHandler extends ChannelInboundHandlerAdapter {

    public static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(32 * 1024, 64 * 1024);

    private final GatewayMetrics gatewayMetrics;

    public BackpressureHandler(GatewayMetrics gatewayMetrics) {
        this.gatewayMetrics = gatewayMetrics;
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        boolean writable = ctx.channel().isWritable();
        ctx.channel().config().setAutoRead(writable);
        if (!writable) {
            gatewayMetrics.recordChannelNotWritable();
        }
        ctx.fireChannelWritabilityChanged();
    }
}
