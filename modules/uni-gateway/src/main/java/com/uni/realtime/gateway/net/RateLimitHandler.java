package com.uni.realtime.gateway.net;

import com.uni.realtime.protocol.GameMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Placeholder: Task 6 only fixes this handler's position in the pipeline (plan.md AC --
 * token-bucket rate limiting keyed by {@code student_id} is Task 7's job, sequenced after
 * this one). Everything passes through untouched for now.
 */
public final class RateLimitHandler extends SimpleChannelInboundHandler<GameMessage> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GameMessage message) {
        ctx.fireChannelRead(message);
    }
}
