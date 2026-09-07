package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.protocol.GameMessage;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

/**
 * Decodes every WS data frame after the first one -- {@link TicketAuthHandler} in
 * {@code auth} already decoded and re-emitted the first {@code JOIN_ROOM} frame as a
 * {@link GameMessage}, and this handler (typed to {@link BinaryWebSocketFrame}) simply lets
 * that already-decoded object pass through untouched.
 */
public final class GameMessageDecoder extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        ctx.fireChannelRead(GameMessage.parseFrom(new ByteBufInputStream(frame.content())));
    }
}
