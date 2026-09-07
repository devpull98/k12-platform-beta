package com.uni.realtime.gateway.fanout;

import com.uni.realtime.gateway.metrics.GatewayMetrics;
import com.uni.realtime.protocol.DeliveryClass;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
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
    private final Broadcaster broadcaster = new Broadcaster(registry, new GatewayMetrics(new SimpleMeterRegistry()));
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

        broadcaster.broadcast("room-1", frame, DeliveryClass.BEST_EFFORT);

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

        broadcaster.broadcast("room-1", Unpooled.wrappedBuffer(new byte[] {1, 2, 3}), DeliveryClass.BEST_EFFORT);

        assertThat((BinaryWebSocketFrame) inRoom1.readOutbound()).isNotNull();
        assertThat((BinaryWebSocketFrame) inRoom2.readOutbound()).isNull();
    }

    @Test
    void should_releaseOriginalFrameExactlyOnce_when_broadcastingToNoClients() {
        ByteBuf frame = Unpooled.wrappedBuffer(new byte[] {9});

        broadcaster.broadcast("empty-room", frame, DeliveryClass.BEST_EFFORT);

        assertThat(frame.refCnt()).isZero();
    }

    @Test
    void should_skipInactiveChannels_when_channelDiedButNotYetRemoved() {
        EmbeddedChannel dead = new EmbeddedChannel();
        registry.add("room-1", dead);
        dead.close();
        clients.add(dead);

        // Must not throw, and must not attempt to write to the dead channel.
        broadcaster.broadcast("room-1", Unpooled.wrappedBuffer(new byte[] {1}), DeliveryClass.BEST_EFFORT);

        assertThat((BinaryWebSocketFrame) dead.readOutbound()).isNull();
    }

    @Test
    void sendToOne_should_deliverToAWritableChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        clients.add(channel);

        broadcaster.sendToOne(channel, Unpooled.wrappedBuffer(new byte[] {1, 2, 3}), DeliveryClass.CRITICAL);

        assertThat((BinaryWebSocketFrame) channel.readOutbound()).isNotNull();
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void sendToOne_should_dropAndReleaseButNotClose_when_bestEffortAndChannelNotWritable() {
        EmbeddedChannel channel = new EmbeddedChannel();
        clients.add(channel);
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));
        channel.write(Unpooled.wrappedBuffer(new byte[1000]));
        assertThat(channel.isWritable()).isFalse();
        ByteBuf frame = Unpooled.wrappedBuffer(new byte[] {1});

        broadcaster.sendToOne(channel, frame, DeliveryClass.BEST_EFFORT);

        assertThat(frame.refCnt()).as("dropped frame must still be released").isZero();
        assertThat(channel.isOpen()).as("BEST_EFFORT must not close a backed-up channel").isTrue();
    }

    @Test
    void sendToOne_should_closeChannel_when_criticalAndChannelNotWritable() {
        EmbeddedChannel channel = new EmbeddedChannel();
        clients.add(channel);
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));
        channel.write(Unpooled.wrappedBuffer(new byte[1000]));
        assertThat(channel.isWritable()).isFalse();

        broadcaster.sendToOne(channel, Unpooled.wrappedBuffer(new byte[] {1}), DeliveryClass.CRITICAL);

        assertThat(channel.isOpen()).as("CRITICAL must close a backed-up channel, per §5.4").isFalse();
    }

    @Test
    void sendToOne_should_releaseFrame_when_channelAlreadyInactive() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close();
        ByteBuf frame = Unpooled.wrappedBuffer(new byte[] {1});

        broadcaster.sendToOne(channel, frame, DeliveryClass.BEST_EFFORT);

        assertThat(frame.refCnt()).isZero();
    }
}
