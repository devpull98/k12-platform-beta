package com.uni.realtime.gateway.routing;

import com.uni.realtime.protocol.GameMessage;

/**
 * What {@code RoomRouteHandler} (Task 13) actually needs from {@link FrameChannelClient}: send
 * a prepared message toward whichever Engine pod owns its room. A seam, not a new concept --
 * same reason {@code TicketVerifier} exists for {@code TicketAuthHandler} -- so a test can
 * capture what would have been sent without opening a real internal-frame-channel connection.
 */
public interface EngineSender {

    void send(GameMessage message);
}
