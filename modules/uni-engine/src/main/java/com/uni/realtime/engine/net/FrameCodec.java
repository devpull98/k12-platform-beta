package com.uni.realtime.engine.net;

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

/**
 * Framing + protobuf codec for the internal Gateway &lt;-&gt; Engine hop (ADR-001): one
 * long-lived TCP connection per (gateway pod, engine pod) pair, multiplexed by
 * {@code room_id} inside each {@link GameMessage} rather than one connection per room.
 *
 * <p>Framing is Netty's own {@link LengthFieldBasedFrameDecoder} / {@link LengthFieldPrepender}
 * on purpose (plan.md Task 4: "không tự viết parser"). Protobuf decode/encode reuses the
 * generated {@link GameMessage} class directly instead of pulling in the generic
 * {@code netty-codec-protobuf} reflection-based codec.
 */
public final class FrameCodec {

    /** ADR-001: a frame past this size is a protocol violation, not a legitimately large one. */
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;

    private static final int LENGTH_FIELD_LENGTH = 4;

    private FrameCodec() {}

    /**
     * Fresh handler instances every call — {@link LengthFieldBasedFrameDecoder} buffers
     * partial frames and must not be shared across connections.
     */
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

    /**
     * plan.md Task 4 AC: an oversized or corrupt frame closes the connection and logs it —
     * it must not take the whole pod down, and {@link LengthFieldBasedFrameDecoder} already
     * refuses to buffer past {@link #MAX_FRAME_LENGTH} so this never risks an OOM.
     */
    private static final class ChannelFaultHandler extends ChannelInboundHandlerAdapter {
        private static final Logger log = LoggerFactory.getLogger(FrameCodec.class);

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("closing internal frame channel {} after decode failure", ctx.channel(), cause);
            ctx.close();
        }
    }
}
