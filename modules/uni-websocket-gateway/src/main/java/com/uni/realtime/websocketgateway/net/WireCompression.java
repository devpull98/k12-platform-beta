package com.uni.realtime.websocketgateway.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jpountz.lz4.LZ4Factory;

/**
 * Wire-level LZ4 framing for the Gateway -> client hop (system-architecture.md SS3.6): compress
 * a serialized {@code GameMessage} only when it is worth the CPU, per the documented threshold.
 * Below the threshold, LZ4's own block overhead plus the flag/length header added here would
 * cost more than the bandwidth saved, so those frames go out as raw protobuf bytes instead.
 *
 * <p>Wire format is one leading flag byte, then the body:
 * <pre>
 *   0x00 &lt;protobuf bytes...&gt;                                -- not compressed
 *   0x01 &lt;4-byte BE original length&gt; &lt;lz4 block...&gt;         -- compressed
 * </pre>
 * The original length travels on the wire because LZ4's block format is not self-describing --
 * the decompressor has to be told exactly how many bytes to produce.
 */
public final class WireCompression {

    /** system-architecture.md SS3.6: "Ap dung khi payload > 150 bytes". */
    static final int COMPRESSION_THRESHOLD_BYTES = 150;

    private static final byte FLAG_RAW = 0x00;
    private static final byte FLAG_LZ4 = 0x01;

    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();

    private WireCompression() {}

    public static ByteBuf encode(byte[] payload) {
        if (payload.length <= COMPRESSION_THRESHOLD_BYTES) {
            return Unpooled.buffer(1 + payload.length)
                    .writeByte(FLAG_RAW)
                    .writeBytes(payload);
        }

        byte[] compressed = FACTORY.fastCompressor().compress(payload);
        return Unpooled.buffer(1 + 4 + compressed.length)
                .writeByte(FLAG_LZ4)
                .writeInt(payload.length)
                .writeBytes(compressed);
    }

    public static byte[] decode(byte[] wireBytes) {
        ByteBuf in = Unpooled.wrappedBuffer(wireBytes);
        byte flag = in.readByte();

        if (flag == FLAG_RAW) {
            byte[] body = new byte[in.readableBytes()];
            in.readBytes(body);
            return body;
        }
        if (flag == FLAG_LZ4) {
            int originalLength = in.readInt();
            byte[] compressed = new byte[in.readableBytes()];
            in.readBytes(compressed);
            byte[] original = new byte[originalLength];
            FACTORY.fastDecompressor().decompress(compressed, 0, original, 0, originalLength);
            return original;
        }
        throw new IllegalArgumentException("unknown WireCompression flag byte: " + flag);
    }
}
