package com.uni.realtime.gameengine.net;

import com.uni.realtime.protocol.GameMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class FrameCodec {
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;

    private static final int LENGTH_FIELD_LENGTH = 4;

    private FrameCodec() {}

    public static List<ChannelHandler> newHandlers() {
        return List.of(
                new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, LENGTH_FIELD_LENGTH, 0, LENGTH_FIELD_LENGTH),
                new LengthFieldPrepender(LENGTH_FIELD_LENGTH),
                new GameMessageDecoder(),
                new GameMessageEncoder(),
                new ChannelFaultHandler());
    }

    private static final class GameMessageDecoder extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) throws Exception {
            ctx.fireChannelRead(GameMessage.parseFrom(new ByteBufInputStream(frame)));
        }
    }

    private static final class GameMessageEncoder extends MessageToByteEncoder<GameMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, GameMessage msg, ByteBuf out) {
            out.writeBytes(msg.toByteArray());
        }
    }

    private static final class ChannelFaultHandler extends ChannelInboundHandlerAdapter {
        private static final Logger log = LoggerFactory.getLogger(FrameCodec.class);

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("closing internal frame channel {} after decode failure", ctx.channel(), cause);
            ctx.close();
        }
    }
}
