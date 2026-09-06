package com.uni.realtime.gateway.fanout;

import com.uni.realtime.protocol.DeliveryClass;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

/**
 * Zero-copy fan-out (§5.4, ADR-006, decision B1): Engine sends exactly one frame per Gateway
 * pod, and multiplying that single frame into one write per local client in the room is this
 * class's entire job -- the fan-out ratio lives here, never upstream of it.
 *
 * <p>§5.4 / plan.md Task 9: a channel reporting {@code !isWritable()} (its own socket is
 * backed up -- one slow client, not the room) is handled per {@code deliveryClass}:
 * {@code BEST_EFFORT} is dropped for that one channel only (the client catches up on the next
 * broadcast), {@code CRITICAL} is never dropped, so that channel is closed instead. Either way
 * the OTHER channels in the same fan-out loop are completely unaffected -- there is no queue
 * here to back up in the first place.
 */
public final class Broadcaster {

    private final RoomRegistry roomRegistry;

    public Broadcaster(RoomRegistry roomRegistry) {
        this.roomRegistry = roomRegistry;
    }

    /**
     * @param frame the already-encoded payload for {@code roomId}. Released exactly once here,
     *              in a {@code finally}, regardless of how many clients it fans out to or
     *              whether writing to any of them throws.
     */
    public void broadcast(String roomId, ByteBuf frame, DeliveryClass deliveryClass) {
        try {
            for (Channel channel : roomRegistry.channelsIn(roomId)) {
                if (!channel.isActive()) {
                    continue;
                }
                if (!channel.isWritable()) {
                    if (deliveryClass == DeliveryClass.CRITICAL) {
                        channel.close();
                    }
                    // BEST_EFFORT (or unspecified): drop for this one slow client only -- it
                    // catches up on the next broadcast, and nothing here queues this one for
                    // later delivery.
                    continue;
                }
                // retainedDuplicate(), never retain() (plan.md Task 8 AC): retain() hands
                // every client the SAME ByteBuf instance with ONE shared reader index --
                // whichever client's socket reads first drains it, and the rest silently
                // get zero bytes. retainedDuplicate() gives each client its own view.
                channel.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
            }
        } finally {
            frame.release();
        }
    }
}
