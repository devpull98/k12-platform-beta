package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.routing.EngineSender;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import com.uni.realtime.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        notifyEngineOfLeave(ctx);
        super.channelInactive(ctx);
    }

    private void notifyEngineOfLeave(ChannelHandlerContext ctx) {
        String roomId = ctx.channel().attr(ChannelAttributes.ROOM_ID).get();
        String studentId = ctx.channel().attr(ChannelAttributes.STUDENT_ID).get();
        if (roomId == null || studentId == null) {
            return;
        }
        String traceId = ctx.channel().attr(ChannelAttributes.TRACE_ID).get();
        engineSender.send(GameMessage.newBuilder()
                .setType(MessageType.STUDENT_LEFT)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .setInternal(InternalHeader.newBuilder().setTraceId(traceId))
                .build());
    }
}
