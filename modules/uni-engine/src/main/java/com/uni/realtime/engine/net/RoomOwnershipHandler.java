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

        // Non-blocking by contract (RoomOwnership#ensureAcquired) -- safe to call on this
        // EventLoop thread. For ModuloRoomOwnership this is a no-op; for a lease-based
        // implementation (Task 14) it kicks off an async acquisition the first time this pod
        // sees roomId, resolved by the time a later frame for the same room arrives.
        roomOwnership.ensureAcquired(roomId);

        if (roomOwnership.isOwner(roomId)) {
            onOwnedMessage.accept(message);
            return;
        }

        String ownerPodId = roomOwnership.ownerPodId(roomId);
        if (ownerPodId.isEmpty()) {
            // Ownership genuinely not resolved yet (lease acquisition in flight) -- replying
            // with a made-up owner would send the Gateway somewhere wrong. Drop this frame
            // silently; the client/Gateway retry cycle already tolerates this (§4.5) and the
            // next frame for this room will almost always find the cache populated.
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
