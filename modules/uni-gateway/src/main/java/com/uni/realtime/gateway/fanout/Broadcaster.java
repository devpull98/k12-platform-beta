package com.uni.realtime.gateway.fanout;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

/**
 * Zero-copy fan-out (§5.4, ADR-006, decision B1): Engine sends exactly one frame per Gateway
 * pod, and multiplying that single frame into one write per local client in the room is this
 * class's entire job -- the fan-out ratio lives here, never upstream of it.
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
    public void broadcast(String roomId, ByteBuf frame) {
        try {
            for (Channel channel : roomRegistry.channelsIn(roomId)) {
                if (channel.isActive()) {
                    // retainedDuplicate(), never retain() (plan.md Task 8 AC): retain() hands
                    // every client the SAME ByteBuf instance with ONE shared reader index --
                    // whichever client's socket reads first drains it, and the rest silently
                    // get zero bytes. retainedDuplicate() gives each client its own view.
                    channel.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
                }
            }
        } finally {
            frame.release();
        }
    }
}
