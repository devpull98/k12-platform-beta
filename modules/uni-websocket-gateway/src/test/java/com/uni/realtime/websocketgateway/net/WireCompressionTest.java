package com.uni.realtime.websocketgateway.net;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * system-architecture.md SS3.6: LZ4 the outbound client frame when it is > 150 bytes, leave it
 * alone below that. Verifies the round trip both sides of the threshold and that the flag byte
 * actually reflects which path was taken -- not just that decode(encode(x)) == x, which a
 * no-op codec would also satisfy.
 */
class WireCompressionTest {

    @Test
    void should_roundTripUnchanged_when_payloadIsBelowTheCompressionThreshold() {
        byte[] payload = "small protobuf payload".getBytes();

        ByteBuf wire = WireCompression.encode(payload);

        assertThat(payload.length).isLessThan(WireCompression.COMPRESSION_THRESHOLD_BYTES);
        assertThat(wire.getByte(0)).as("below threshold must go out raw, not LZ4-framed").isZero();
        assertThat(WireCompression.decode(readAllAndRelease(wire))).isEqualTo(payload);
    }

    @Test
    void should_flagAsRaw_when_payloadIsExactlyAtTheThreshold() {
        byte[] payload = repeatingBytes(WireCompression.COMPRESSION_THRESHOLD_BYTES);

        ByteBuf wire = WireCompression.encode(payload);

        assertThat(wire.getByte(0)).as("threshold is > 150, so exactly 150 must still be raw").isZero();
        assertThat(WireCompression.decode(readAllAndRelease(wire))).isEqualTo(payload);
    }

    @Test
    void should_compressAndRoundTrip_when_payloadIsOneByteOverTheThreshold() {
        byte[] payload = repeatingBytes(WireCompression.COMPRESSION_THRESHOLD_BYTES + 1);

        ByteBuf wire = WireCompression.encode(payload);

        assertThat(wire.getByte(0)).as("one byte over threshold must take the LZ4 path").isOne();
        assertThat(WireCompression.decode(readAllAndRelease(wire))).isEqualTo(payload);
    }

    @Test
    void should_actuallyShrinkTheWireSize_when_payloadIsLargeAndCompressible() {
        byte[] payload = repeatingBytes(4096);

        ByteBuf wire = WireCompression.encode(payload);

        assertThat(wire.readableBytes())
                .as("this is the whole point of turning it on -- a repetitive payload must come out smaller")
                .isLessThan(payload.length);
        wire.release();
    }

    @Test
    void should_roundTrip_when_payloadIsLargeAndIncompressible() {
        byte[] payload = new byte[2048];
        new Random(42).nextBytes(payload);

        ByteBuf wire = WireCompression.encode(payload);

        assertThat(WireCompression.decode(readAllAndRelease(wire))).isEqualTo(payload);
    }

    private static byte[] repeatingBytes(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'x');
        return bytes;
    }

    private static byte[] readAllAndRelease(ByteBuf buf) {
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }
}
