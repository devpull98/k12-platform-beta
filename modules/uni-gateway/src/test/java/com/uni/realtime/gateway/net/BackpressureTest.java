package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.fanout.Broadcaster;
import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.protocol.DeliveryClass;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9 verification (plan.md): the one backpressure mechanism (§10.2), and the mandated
 * scenario -- a slow client must not drag down other clients in the same room.
 *
 * <p>{@code EmbeddedChannel} completes a {@code writeAndFlush} synchronously (there is no real
 * socket to back up against), so an already-flushed write can never be used to force
 * {@code isWritable()} false. An <b>unflushed</b> {@code write()} left pending against a small
 * watermark does trip it correctly, using the same {@code ChannelOutboundBuffer} accounting a
 * real socket uses -- that is how every test below simulates "this client's socket is slow."
 */
class BackpressureTest {

    @Test
    void should_toggleAutoReadOffAndOn_when_writabilityChanges() {
        EmbeddedChannel channel = new EmbeddedChannel(new BackpressureHandler(new SimpleMeterRegistry()));
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));
        assertThat(channel.config().isAutoRead()).isTrue();

        channel.write(Unpooled.wrappedBuffer(new byte[1000])); // unflushed: pending bytes trip the high mark

        assertThat(channel.isWritable()).isFalse();
        assertThat(channel.config().isAutoRead()).isFalse();

        channel.flush(); // drains the pending write, dropping back below the low mark

        assertThat(channel.isWritable()).isTrue();
        assertThat(channel.config().isAutoRead()).isTrue();
    }

    @Test
    void should_incrementNotWritableCounter_when_channelBecomesUnwritable() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new BackpressureHandler(meterRegistry));
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));

        channel.write(Unpooled.wrappedBuffer(new byte[1000]));

        assertThat(meterRegistry.get("channel_not_writable_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void should_dropBestEffortFrame_when_channelIsNotWritable() {
        RoomRegistry registry = new RoomRegistry();
        Broadcaster broadcaster = new Broadcaster(registry);
        EmbeddedChannel slowClient = unwritableChannel();
        registry.add("room-1", slowClient);

        broadcaster.broadcast("room-1", payload("delta"), DeliveryClass.BEST_EFFORT);

        assertThat(pendingFrameCount(slowClient)).as("no new frame queued behind the pre-existing pending write").isZero();
        assertThat(slowClient.isOpen()).isTrue();
    }

    @Test
    void should_closeChannel_when_criticalFrameCannotBeWritten() {
        RoomRegistry registry = new RoomRegistry();
        Broadcaster broadcaster = new Broadcaster(registry);
        EmbeddedChannel slowClient = unwritableChannel();
        registry.add("room-1", slowClient);

        broadcaster.broadcast("room-1", payload("ANSWER_ACK"), DeliveryClass.CRITICAL);

        assertThat(slowClient.isOpen()).as("a Critical message must never be silently dropped").isFalse();
    }

    @Test
    void should_notAffectOtherClientsInTheSameRoom_when_oneClientIsSlow() {
        // The mandated case (plan.md Task 9 Verification): a slow client must not drag down
        // other clients in the same room. Broadcaster's loop writes to each channel
        // independently, so the slow one being skipped/closed must have zero effect on the rest.
        RoomRegistry registry = new RoomRegistry();
        Broadcaster broadcaster = new Broadcaster(registry);
        EmbeddedChannel slowClient = unwritableChannel();
        EmbeddedChannel healthyClient = new EmbeddedChannel();
        registry.add("room-1", slowClient);
        registry.add("room-1", healthyClient);

        broadcaster.broadcast("room-1", payload("delta"), DeliveryClass.BEST_EFFORT);

        assertThat(pendingFrameCount(slowClient)).isZero();
        BinaryWebSocketFrame received = healthyClient.readOutbound();
        assertThat(received).as("the healthy client must receive its frame regardless of the slow one").isNotNull();
        received.release();
    }

    /** A channel with a pending, unflushed write large enough to trip a tiny watermark. */
    private static EmbeddedChannel unwritableChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));
        channel.write(Unpooled.wrappedBuffer(new byte[1000]));
        assertThat(channel.isWritable()).as("test setup: channel must already be unwritable").isFalse();
        return channel;
    }

    /** Flushes the channel and counts whatever comes out -- the setup write plus anything Broadcaster added. */
    private static int pendingFrameCount(EmbeddedChannel channel) {
        channel.flush();
        int count = 0;
        Object message;
        while ((message = channel.readOutbound()) != null) {
            count++;
            if (message instanceof ByteBuf buf) {
                buf.release();
            } else if (message instanceof BinaryWebSocketFrame frame) {
                frame.release();
            }
        }
        return count - 1; // subtract the one pre-existing setup write
    }

    private static ByteBuf payload(String text) {
        return Unpooled.wrappedBuffer(text.getBytes());
    }
}
