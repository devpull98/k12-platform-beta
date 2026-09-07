package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.fanout.Broadcaster;
import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.protocol.ConnectionDegraded;
import com.uni.realtime.protocol.DeliveryClass;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RoutingStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.Set;

/**
 * Task 13: the Gateway-side half of {@link com.uni.realtime.gateway.routing.FrameChannelClient}'s
 * {@code onResponse} -- decides whether an Engine response is a personal reply or a room
 * broadcast, and gets {@code InternalHeader} off the wire before it ever reaches a client
 * (proto contract on {@code GameMessage.internal}: present on the internal hop only).
 */
public final class EngineResponseRouter {

    private final RoomRegistry roomRegistry;
    private final Broadcaster broadcaster;

    public EngineResponseRouter(RoomRegistry roomRegistry, Broadcaster broadcaster) {
        this.roomRegistry = roomRegistry;
        this.broadcaster = broadcaster;
    }

    public void route(GameMessage message) {
        if (message.getInternal().getRoutingStatus() == RoutingStatus.NOT_OWNER) {
            // FrameChannelClient already re-learned the route from this response (§8.2) and
            // this reply carries no payload (RoomOwnershipHandler builds it with no oneof case
            // set) -- there is nothing to deliver. The original submission is not retried here;
            // §8.2 says only the NEXT one goes to the right pod.
            return;
        }

        GameMessage forClient = message.toBuilder().clearInternal().build();
        ByteBuf frame = Unpooled.wrappedBuffer(forClient.toByteArray());

        if (message.getType() == MessageType.ANSWER_ACK) {
            sendToOneStudent(message.getRoomId(), message.getStudentId(), frame);
        } else {
            broadcaster.broadcast(message.getRoomId(), frame, message.getInternal().getDeliveryClass());
        }
    }

    /**
     * §9.7: an Engine pod connection dropping must degrade, never close, whichever WebSockets
     * were routed through it. {@code CRITICAL} here means the usual backpressure rule still
     * applies (a stuck client's own socket may close) -- this call itself never closes anything.
     */
    public void broadcastConnectionDegraded(Set<String> roomIds) {
        for (String roomId : roomIds) {
            GameMessage degraded = GameMessage.newBuilder()
                    .setType(MessageType.CONNECTION_DEGRADED)
                    .setRoomId(roomId)
                    .setConnectionDegraded(ConnectionDegraded.newBuilder()
                            .setReason(ConnectionDegraded.Reason.ENGINE_UNREACHABLE)
                            .setMessage("engine pod connection lost")
                            .setRetryable(true))
                    .build();
            broadcaster.broadcast(roomId, Unpooled.wrappedBuffer(degraded.toByteArray()), DeliveryClass.CRITICAL);
        }
    }

    /** ANSWER_ACK is personal (§5.4) -- {@link Broadcaster}'s whole-room fan-out is the wrong tool for it. */
    private void sendToOneStudent(String roomId, String studentId, ByteBuf frame) {
        try {
            for (Channel channel : roomRegistry.channelsIn(roomId)) {
                if (studentId.equals(channel.attr(ChannelAttributes.STUDENT_ID).get())) {
                    channel.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
                    return;
                }
            }
        } finally {
            frame.release();
        }
    }
}
