package com.uni.realtime.gameengine.net;

import com.uni.realtime.gameengine.room.RoomOwnership;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import com.uni.realtime.protocol.RoutingStatus;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.function.Consumer;

public final class RoomOwnershipHandler extends SimpleChannelInboundHandler<GameMessage> {

    private final RoomOwnership roomOwnership;
    private final Consumer<GameMessage> onOwnedMessage;

    public RoomOwnershipHandler(RoomOwnership roomOwnership, Consumer<GameMessage> onOwnedMessage) {
        this.roomOwnership = roomOwnership;
        this.onOwnedMessage = onOwnedMessage;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GameMessage message) {
        String roomId = message.getRoomId();

        roomOwnership.ensureAcquired(roomId);

        if (roomOwnership.isOwner(roomId)) {
            onOwnedMessage.accept(message);
            return;
        }

        String ownerPodId = roomOwnership.ownerPodId(roomId);
        if (ownerPodId.isEmpty()) {
            return;
        }

        GameMessage notOwner = GameMessage.newBuilder()
                .setRoomId(roomId)
                .setStudentId(message.getStudentId())
                .setSequence(message.getSequence())
                .setInternal(InternalHeader.newBuilder()
                        .setOwnerPodId(ownerPodId)
                        .setRoutingStatus(RoutingStatus.NOT_OWNER))
                .build();
        ctx.writeAndFlush(notOwner);
    }
}
