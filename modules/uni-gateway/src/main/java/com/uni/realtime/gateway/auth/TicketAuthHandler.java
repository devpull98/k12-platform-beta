package com.uni.realtime.gateway.auth;

import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.gateway.metrics.GatewayMetrics;
import com.uni.realtime.gateway.net.ChannelAttributes;
import com.uni.realtime.protocol.GameMessage;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

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
 *
 * <p>This is also the only point in the pipeline where the room a channel belongs to becomes
 * known, so it registers the channel into {@link RoomRegistry} here (Task 8) -- removal
 * happens in {@code RoomRouteHandler}, which (unlike this handler) stays in the pipeline for
 * the channel's whole life.
 *
 * <p>Task 12 / §15.3: a fresh {@code trace_id} is generated here (not carried by the ticket)
 * and bound alongside identity, so {@code RoomRouteHandler} can stamp it into
 * {@code InternalHeader} for every message this connection ever sends onward. Every successful
 * verification also counts toward {@code handshake_rate}.
 */
public final class TicketAuthHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(TicketAuthHandler.class);

    private final TicketVerifier ticketVerifier;
    private final RoomRegistry roomRegistry;
    private final GatewayMetrics gatewayMetrics;

    public TicketAuthHandler(TicketVerifier ticketVerifier, RoomRegistry roomRegistry, GatewayMetrics gatewayMetrics) {
        this.ticketVerifier = ticketVerifier;
        this.roomRegistry = roomRegistry;
        this.gatewayMetrics = gatewayMetrics;
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

        ChannelAttributes.bind(ctx.channel(), claims, UUID.randomUUID().toString());
        roomRegistry.add(claims.roomId(), ctx.channel());
        gatewayMetrics.recordHandshake();
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(message);
    }
}
