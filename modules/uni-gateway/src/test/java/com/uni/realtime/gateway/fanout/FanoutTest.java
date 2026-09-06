package com.uni.realtime.gateway.fanout;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 verification (plan.md): the mandated 12-client fan-out case. A single-client version
 * of this test passes even when the production code wrongly uses {@code retain()} instead of
 * {@code retainedDuplicate()} -- both share the exact same reader index across every client's
 * frame, so client 0 reading its bytes silently drains what clients 1..11 would have received.
 * Only reading back EVERY client's full payload, in order, surfaces that bug -- which is
 * exactly why the plan bans shrinking this to fewer clients.
 */
class FanoutTest {

    private static final int CLIENT_COUNT = 12;

    private final RoomRegistry registry = new RoomRegistry();
    private final Broadcaster broadcaster = new Broadcaster(registry);
    private final List<EmbeddedChannel> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        clients.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    @Test
    void should_deliverFullPayloadToEveryClient_when_broadcastingToTwelveClients() {
        for (int i = 0; i < CLIENT_COUNT; i++) {
            EmbeddedChannel channel = new EmbeddedChannel();
            registry.add("room-1", channel);
            clients.add(channel);
        }
        byte[] payload = "room-1 snapshot delta payload".getBytes(StandardCharsets.UTF_8);
        ByteBuf frame = Unpooled.wrappedBuffer(payload);

        broadcaster.broadcast("room-1", frame);

        for (int i = 0; i < clients.size(); i++) {
            BinaryWebSocketFrame received = clients.get(i).readOutbound();
            assertThat(received).as("client %d must receive a frame", i).isNotNull();

            byte[] receivedBytes = new byte[received.content().readableBytes()];
            received.content().readBytes(receivedBytes);
            received.release();

            assertThat(receivedBytes)
                    .as("client %d must receive the FULL payload -- a shared reader index "
                            + "(retain() instead of retainedDuplicate()) would leave everyone "
                            + "after the first reader with zero bytes here", i)
                    .isEqualTo(payload);
        }
    }

    @Test
    void should_onlyDeliverToChannelsInThatRoom_when_multipleRoomsExist() {
        EmbeddedChannel inRoom1 = new EmbeddedChannel();
        EmbeddedChannel inRoom2 = new EmbeddedChannel();
        registry.add("room-1", inRoom1);
        registry.add("room-2", inRoom2);
        clients.add(inRoom1);
        clients.add(inRoom2);

        broadcaster.broadcast("room-1", Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));

        assertThat((BinaryWebSocketFrame) inRoom1.readOutbound()).isNotNull();
        assertThat((BinaryWebSocketFrame) inRoom2.readOutbound()).isNull();
    }

    @Test
    void should_releaseOriginalFrameExactlyOnce_when_broadcastingToNoClients() {
        ByteBuf frame = Unpooled.wrappedBuffer(new byte[] {9});

        broadcaster.broadcast("empty-room", frame);

        assertThat(frame.refCnt()).isZero();
    }

    @Test
    void should_skipInactiveChannels_when_channelDiedButNotYetRemoved() {
        EmbeddedChannel dead = new EmbeddedChannel();
        registry.add("room-1", dead);
        dead.close();
        clients.add(dead);

        // Must not throw, and must not attempt to write to the dead channel.
        broadcaster.broadcast("room-1", Unpooled.wrappedBuffer(new byte[] {1}));

        assertThat((BinaryWebSocketFrame) dead.readOutbound()).isNull();
    }
}
