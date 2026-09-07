package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.routing.EngineSender;
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
 * hint, it is a security event -- the channel closes. {@code student_id} gets the identical
 * treatment for the identical reason: the envelope's {@code student_id} field is otherwise
 * client-writable, and {@code SubmitAnswer}'s own payload carries no identity of its own to
 * fall back on -- trusting a client-supplied one would let one student submit as another.
 *
 * <p>This is the last Gateway-side stop before a message goes to Engine (Task 13): it stamps
 * {@code InternalHeader.trace_id} from {@link ChannelAttributes#TRACE_ID} (§15.3, Task 12) and
 * calls {@link EngineSender#send}, so nothing downstream of here needs to know about tracing
 * or routing.
 *
 * <p>This is also where a dying channel is deregistered from {@link RoomRegistry} (Task 8):
 * {@code TicketAuthHandler} removes itself from the pipeline right after the join, so it
 * cannot see this channel's eventual {@code channelInactive} -- this handler stays for the
 * whole connection and does.
 */
public final class RoomRouteHandler extends SimpleChannelInboundHandler<GameMessage> {

    private static final Logger log = LoggerFactory.getLogger(RoomRouteHandler.class);

    private final RoomRegistry roomRegistry;
    private final EngineSender engineSender;

    public RoomRouteHandler(RoomRegistry roomRegistry, EngineSender engineSender) {
        this.roomRegistry = roomRegistry;
        this.engineSender = engineSender;
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

        String boundStudentId = ctx.channel().attr(ChannelAttributes.STUDENT_ID).get();
        String payloadStudentId = message.getStudentId();
        if (!payloadStudentId.isEmpty() && !payloadStudentId.equals(boundStudentId)) {
            log.warn("security event: closing channel {} -- payload student_id '{}' disagrees with bound student_id '{}'",
                    ctx.channel(), payloadStudentId, boundStudentId);
            ctx.close();
            return;
        }

        String traceId = ctx.channel().attr(ChannelAttributes.TRACE_ID).get();
        GameMessage prepared = message.toBuilder()
                .setRoomId(boundRoomId)
                .setStudentId(boundStudentId)
                .setInternal(InternalHeader.newBuilder().setTraceId(traceId))
                .build();
        engineSender.send(prepared);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        roomRegistry.remove(ctx.channel());
        super.channelInactive(ctx);
    }
}
