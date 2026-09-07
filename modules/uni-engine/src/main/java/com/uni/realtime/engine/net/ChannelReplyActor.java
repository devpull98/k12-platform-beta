package com.uni.realtime.engine.net;

import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import io.netty.channel.Channel;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * The far end of a {@code RoomActor} {@code replyTo}/{@code broadcastTarget}
 * ({@code ActorRef<GameMessage>}) that actually crosses back out to a Gateway pod (Task 13).
 * One instance per accepted internal-frame-channel connection, spawned by {@link
 * com.uni.realtime.engine.room.RoomSupervisor} the first time it needs to answer that
 * connection -- {@code RoomActor} itself stays exactly as transport-agnostic as Task 3 left it;
 * this is the only place that knows a {@link Channel} exists on the other end of a reply.
 *
 * <p>Stamps {@code InternalHeader} here, not in {@code RoomActor}: neither {@code RoomActor} nor
 * {@code RoomState} know this pod's id or how to classify a message's delivery class, and
 * putting it here means every outbound path (direct reply AND room broadcast) gets it exactly
 * once, in the one place a {@link Channel} is actually written to.
 */
public final class ChannelReplyActor {

    private ChannelReplyActor() {}

    public static Behavior<GameMessage> create(Channel channel, String podId) {
        return Behaviors.receiveMessage(message -> {
            GameMessage stamped = message.toBuilder()
                    .setInternal(InternalHeader.newBuilder()
                            .setOwnerPodId(podId)
                            .setDeliveryClass(DeliveryClassifier.classify(message.getType())))
                    .build();
            channel.writeAndFlush(stamped);
            return Behaviors.same();
        });
    }
}
