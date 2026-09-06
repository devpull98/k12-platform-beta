package com.uni.realtime.engine.net;

import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.SubmitAnswer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 verification (plan.md): the internal frame channel's length-prefixed framing plus
 * protobuf codec, exercised with {@code EmbeddedChannel} and no real socket
 * (test-patterns.mdc).
 */
class FrameCodecTest {

    private final EmbeddedChannel channel = new EmbeddedChannel(
            FrameCodec.newHandlers().toArray(new ChannelHandler[0]));

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void should_roundTripGameMessage_when_writtenThenReadBack() {
        GameMessage original = sampleMessage();

        // LengthFieldPrepender emits the length prefix and the payload as two separate
        // outbound writes (it never copies them together) -- both land on the same real TCP
        // connection either way, but EmbeddedChannel models them as two discrete messages.
        assertThat(channel.writeOutbound(original)).isTrue();
        CompositeByteBuf framed = Unpooled.compositeBuffer();
        ByteBuf part;
        while ((part = channel.readOutbound()) != null) {
            framed.addComponent(true, part);
        }

        assertThat(channel.writeInbound(framed)).isTrue();
        GameMessage decoded = channel.readInbound();
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void should_reassembleFrame_when_deliveredOneByteAtATime() throws Exception {
        GameMessage original = sampleMessage();
        byte[] framed = frameBytes(original);

        for (int i = 0; i < framed.length - 1; i++) {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {framed[i]}));
            assertThat((GameMessage) channel.readInbound())
                    .as("no message before the last byte of the frame has arrived (byte %d/%d)", i, framed.length)
                    .isNull();
        }
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {framed[framed.length - 1]}));

        GameMessage decoded = channel.readInbound();
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void should_closeChannelWithoutThrowing_when_declaredFrameLengthExceedsMax() {
        ByteBuf oversizedHeader = Unpooled.buffer(4).writeInt(FrameCodec.MAX_FRAME_LENGTH + 1);

        channel.writeInbound(oversizedHeader); // must not throw out of writeInbound (§13.2: no OOM, no crash)

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void should_keepChannelOpen_when_declaredFrameLengthIsAtTheLimit() {
        // LengthFieldBasedFrameDecoder counts the 4-byte length field itself towards
        // maxFrameLength, so the largest payload length that still fits is MAX - 4.
        ByteBuf boundaryHeader = Unpooled.buffer(4).writeInt(FrameCodec.MAX_FRAME_LENGTH - 4);

        channel.writeInbound(boundaryHeader); // still waiting on the rest of the body

        assertThat(channel.isOpen()).isTrue();
    }

    private static GameMessage sampleMessage() {
        return GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId("room-101")
                .setStudentId("student-7")
                .setSequence(42L)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1").addAnswerIds("a"))
                .build();
    }

    private static byte[] frameBytes(GameMessage message) {
        byte[] payload = message.toByteArray();
        ByteBuf buffer = Unpooled.buffer(4 + payload.length)
                .writeInt(payload.length)
                .writeBytes(payload);
        byte[] framed = new byte[buffer.readableBytes()];
        buffer.readBytes(framed);
        buffer.release();
        return framed;
    }
}
