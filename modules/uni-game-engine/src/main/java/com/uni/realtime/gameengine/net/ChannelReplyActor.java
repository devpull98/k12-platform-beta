package com.uni.realtime.gameengine.net;

import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import io.netty.channel.Channel;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

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
