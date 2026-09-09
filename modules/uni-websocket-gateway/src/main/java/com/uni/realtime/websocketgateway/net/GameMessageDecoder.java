package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.protocol.GameMessage;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

public final class GameMessageDecoder extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        ctx.fireChannelRead(GameMessage.parseFrom(new ByteBufInputStream(frame.content())));
    }
}
