package com.uni.realtime.gateway.net;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    private final Counter notWritableCounter;

    public BackpressureHandler(MeterRegistry meterRegistry) {
        this.notWritableCounter = Counter.builder("channel_not_writable_total").register(meterRegistry);
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
