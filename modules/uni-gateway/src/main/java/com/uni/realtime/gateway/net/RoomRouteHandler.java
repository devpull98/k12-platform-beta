package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
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
 * <p>This is the last Gateway-side stop before a message would go to Engine, so it is also
 * where {@code InternalHeader.trace_id} gets stamped from {@link ChannelAttributes#TRACE_ID}
 * (§15.3, Task 12) -- whichever handler eventually calls {@code FrameChannelClient.send(...)}
 * (Task 13) does not need to know about tracing at all.
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

        String traceId = ctx.channel().attr(ChannelAttributes.TRACE_ID).get();
        GameMessage prepared = message.toBuilder()
                .setRoomId(boundRoomId)
                .setInternal(InternalHeader.newBuilder().setTraceId(traceId))
                .build();
        ctx.fireChannelRead(prepared);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        roomRegistry.remove(ctx.channel());
        super.channelInactive(ctx);
    }
}
