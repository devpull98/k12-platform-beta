package com.uni.realtime.engine.net;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9 verification (plan.md), Engine side: the same backpressure primitive as the Gateway
 * (§10.2) applied to the internal frame channel's accepted connection -- if this pod can't
 * keep writing responses fast enough, it stops reading more requests off the connection until
 * it drains. {@code EmbeddedChannel} completes a flushed write synchronously, so (as in the
 * Gateway's {@code BackpressureTest}) an unflushed {@code write()} left pending against a tiny
 * watermark is what actually trips {@code isWritable()} here, using the same
 * {@code ChannelOutboundBuffer} accounting a real socket relies on.
 */
class BackpressureTest {

    @Test
    void should_toggleAutoReadOffAndOn_when_writabilityChanges() {
        ChannelHandler handler = FrameChannelServer.newBackpressureHandler(new SimpleMeterRegistry());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));
        assertThat(channel.config().isAutoRead()).isTrue();

        channel.write(Unpooled.wrappedBuffer(new byte[1000])); // unflushed: pending bytes trip the high mark

        assertThat(channel.isWritable()).isFalse();
        assertThat(channel.config().isAutoRead()).isFalse();

        channel.flush();

        assertThat(channel.isWritable()).isTrue();
        assertThat(channel.config().isAutoRead()).isTrue();
    }

    @Test
    void should_incrementNotWritableCounter_when_channelBecomesUnwritable() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(FrameChannelServer.newBackpressureHandler(meterRegistry));
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));

        channel.write(Unpooled.wrappedBuffer(new byte[1000]));

        assertThat(meterRegistry.get("channel_not_writable_total").counter().count()).isEqualTo(1.0);
    }
}
