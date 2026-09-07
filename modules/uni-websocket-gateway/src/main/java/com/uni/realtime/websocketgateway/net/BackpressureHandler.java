package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.WriteBufferWaterMark;

/**
 * The one backpressure mechanism reused at every hop (§10.2, plan.md Task 9): when a channel
 * can no longer keep up writing, stop reading more into it, and resume once it drains. Applied
 * consistently -- WS client channels and both ends of the Gateway&lt;-&gt;Engine internal
 * connection -- a slow consumer anywhere in the chain propagates backpressure upstream without
 * any single handler needing to know about the hop before or after it.
 */
public final class BackpressureHandler extends ChannelInboundHandlerAdapter {

    /** plan.md Task 9 AC: 32 KB low / 64 KB high, per channel. */
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
