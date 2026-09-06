package com.uni.realtime.engine.net;

import com.uni.realtime.engine.room.RoomOwnership;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import com.uni.realtime.protocol.RoutingStatus;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.function.Consumer;

/**
 * The trust boundary between the wire and {@code RoomActor} (Task 10 AC): a room this pod
 * owns is handed to {@code onOwnedMessage}; a room it does not own gets a {@code NOT_OWNER}
 * reply carrying the real owner so the Gateway's RouteCache can correct itself (§4.5, §8.2) --
 * it is never silently dropped or forwarded to another Engine pod (no such internal-to-internal
 * hop exists in Phase 1).
 */
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

        if (roomOwnership.isOwner(roomId)) {
            onOwnedMessage.accept(message);
            return;
        }

        GameMessage notOwner = GameMessage.newBuilder()
                .setRoomId(roomId)
                .setStudentId(message.getStudentId())
                .setSequence(message.getSequence())
                .setInternal(InternalHeader.newBuilder()
                        .setOwnerPodId(roomOwnership.ownerPodId(roomId))
                        .setRoutingStatus(RoutingStatus.NOT_OWNER))
                .build();
        ctx.writeAndFlush(notOwner);
    }
}
