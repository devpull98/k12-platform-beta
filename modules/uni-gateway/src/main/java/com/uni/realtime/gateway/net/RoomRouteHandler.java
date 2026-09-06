package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.protocol.GameMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §10.6: {@code room_id} is authoritative only from {@link ChannelAttributes}, bound once at
 * handshake. An empty payload {@code room_id} is tolerated (the client has no reason to fill
 * it in) and gets overwritten; a payload that disagrees with the bound value is not a routing
 * hint, it is a security event -- the channel closes.
 *
 * <p>Actually dispatching a validated message to the room's owning Engine pod is Task 5's
 * {@code RouteCache} / Task 9's backpressure chain, not this handler's job -- this only
 * establishes the trust boundary and forwards.
 *
 * <p>This is also where a dying channel is deregistered from {@link RoomRegistry} (Task 8):
 * {@code TicketAuthHandler} removes itself from the pipeline right after the join, so it
 * cannot see this channel's eventual {@code channelInactive} -- this handler stays for the
 * whole connection and does.
 */
public final class RoomRouteHandler extends SimpleChannelInboundHandler<GameMessage> {

    private static final Logger log = LoggerFactory.getLogger(RoomRouteHandler.class);

    private final RoomRegistry roomRegistry;

    public RoomRouteHandler(RoomRegistry roomRegistry) {
        this.roomRegistry = roomRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GameMessage message) {
        String boundRoomId = ctx.channel().attr(ChannelAttributes.ROOM_ID).get();
        String payloadRoomId = message.getRoomId();

        if (!payloadRoomId.isEmpty() && !payloadRoomId.equals(boundRoomId)) {
            log.warn("security event: closing channel {} -- payload room_id '{}' disagrees with bound room_id '{}'",
                    ctx.channel(), payloadRoomId, boundRoomId);
            ctx.close();
            return;
        }

        GameMessage corrected = payloadRoomId.equals(boundRoomId)
                ? message
                : message.toBuilder().setRoomId(boundRoomId).build();
        ctx.fireChannelRead(corrected);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        roomRegistry.remove(ctx.channel());
        super.channelInactive(ctx);
    }
}
