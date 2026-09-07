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

        DeliveryClass deliveryClass = message.getInternal().getDeliveryClass();
        // toBuilder()/build()/toByteArray() allocate (Task 13 review flagged this as GC churn),
        // but exactly once per Engine response, not once per client: Broadcaster fans the same
        // encoded frame out to every channel in the room via retainedDuplicate() (zero-copy),
        // so this cost is already amortized across the room's size, not multiplied by it.
        // Avoiding it entirely would mean hand-rolling protobuf field removal instead of using
        // the immutable-message API -- not worth it to strip one field.
        GameMessage forClient = message.toBuilder().clearInternal().build();
        ByteBuf frame = Unpooled.wrappedBuffer(forClient.toByteArray());

        // A non-empty student_id addresses this message to one student (AnswerAck always has
        // one; RoomActor.onJoinRoom now stamps its personal full-snapshot reply the same way)
        // -- everything else (coalescing flushes, GameOver, ...) is a genuine room broadcast and
        // carries no student_id at all.
        if (!message.getStudentId().isEmpty()) {
            sendToOneStudent(message.getRoomId(), message.getStudentId(), frame, deliveryClass);
        } else {
            broadcaster.broadcast(message.getRoomId(), frame, deliveryClass);
        }
    }

    /**
     * §9.7: an Engine pod connection dropping must degrade, never close, whichever WebSockets
     * were routed through it -- stamped {@code BEST_EFFORT}, not {@code CRITICAL}, specifically
     * because {@link Broadcaster} closes a channel that is {@code !isWritable()} for
     * {@code CRITICAL} traffic (the correct rule for everything else Critical, e.g. ANSWER_ACK,
     * per §5.4). Applied to this message that rule would close exactly the socket §9.7 says to
     * hold open, the instant a client that happens to be backed up needs it most. A dropped
     * notice for a momentarily backed-up client is the acceptable trade here, not a closed one.
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
            broadcaster.broadcast(roomId, Unpooled.wrappedBuffer(degraded.toByteArray()), DeliveryClass.BEST_EFFORT);
        }
    }

    /**
     * ANSWER_ACK (and a join's personal snapshot) is addressed to one student (§5.4) --
     * {@link Broadcaster#broadcast}'s whole-room fan-out is the wrong tool for it, but
     * {@link Broadcaster#sendToOne} still applies the identical backpressure rule.
     * {@link RoomRegistry#channelFor} is O(1) -- this used to scan every channel in the room
     * per lookup, on the hottest path in the system (every SUBMIT_ANSWER).
     */
    private void sendToOneStudent(String roomId, String studentId, ByteBuf frame, DeliveryClass deliveryClass) {
        roomRegistry.channelFor(roomId, studentId)
                .ifPresentOrElse(channel -> broadcaster.sendToOne(channel, frame, deliveryClass), frame::release);
    }
}
