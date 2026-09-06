package com.uni.realtime.gateway.auth;

import com.uni.realtime.gateway.net.ChannelAttributes;
import com.uni.realtime.protocol.GameMessage;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first WebSocket data frame on a connection must be a {@code JOIN_ROOM} carrying the
 * one-time ticket (§3.4). This handler verifies it exactly once, binds
 * {@link ChannelAttributes}, then removes itself so nothing after it re-checks a signature
 * per packet -- the ticket has no relevance once identity is bound to the channel.
 *
 * <p>The verified {@code JoinRoom} message is re-emitted as an already-decoded
 * {@link GameMessage} (not the raw frame) so it is not parsed twice: the codec handler later
 * in the pipeline is typed to {@link BinaryWebSocketFrame} and simply passes a
 * {@link GameMessage} object straight through.
 */
public final class TicketAuthHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(TicketAuthHandler.class);

    private final TicketVerifier ticketVerifier;

    public TicketAuthHandler(TicketVerifier ticketVerifier) {
        this.ticketVerifier = ticketVerifier;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        GameMessage message = GameMessage.parseFrom(new ByteBufInputStream(frame.content()));

        if (message.getPayloadCase() != GameMessage.PayloadCase.JOIN_ROOM) {
            log.warn("closing channel {}: first frame was {}, not JOIN_ROOM",
                    ctx.channel(), message.getPayloadCase());
            ctx.close();
            return;
        }

        TicketClaims claims;
        try {
            claims = ticketVerifier.verify(message.getJoinRoom().getTicket());
        } catch (TicketRejectedException e) {
            log.warn("closing channel {}: ticket rejected ({})", ctx.channel(), e.getMessage());
            ctx.close();
            return;
        }

        ChannelAttributes.bind(ctx.channel(), claims);
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(message);
    }
}
