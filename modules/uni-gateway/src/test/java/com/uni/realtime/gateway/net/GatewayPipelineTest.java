package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.auth.TicketAuthHandler;
import com.uni.realtime.gateway.auth.TicketClaims;
import com.uni.realtime.gateway.auth.TicketRejectedException;
import com.uni.realtime.gateway.auth.TicketVerifier;
import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.gateway.metrics.GatewayMetrics;
import com.uni.realtime.gateway.routing.EngineSender;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.SubmitAnswer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6 verification (plan.md): fixed pipeline order, ticket-gated handshake, and the
 * room_id trust boundary -- all with {@code EmbeddedChannel} and no real socket
 * (test-patterns.mdc). The HTTP/WS upgrade machinery itself (HttpServerCodec,
 * HttpObjectAggregator, WebSocketServerProtocolHandler) is Netty's own well-tested code, so
 * the behavioral tests below exercise only the application handlers after it -- exactly what
 * they would see once a real handshake has completed.
 *
 * <p>Task 13: {@code RoomRouteHandler} now forwards through {@link EngineSender} instead of
 * {@code fireChannelRead}, so tests that used to read the forwarded message back off the
 * channel with {@code channel.readInbound()} now read it off a {@link CapturingEngineSender}.
 */
class GatewayPipelineTest {

    private static final TicketClaims ROOM_1_CLAIMS =
            new TicketClaims("student-1", "room-1", "session-1", List.of("student"));

    @Test
    void should_assembleHandlersInFixedOrderWithNoTlsHandler_when_pipelineBuilt() {
        EmbeddedChannel channel = new EmbeddedChannel();
        GatewayPipeline.addTo(channel.pipeline(), fixedVerifier(ROOM_1_CLAIMS), new RoomRegistry(),
                new GatewayMetrics(new SimpleMeterRegistry()), new IpAdmissionController(), new CapturingEngineSender());

        List<String> handlerClassNames = new ArrayList<>();
        for (Map.Entry<String, ChannelHandler> entry : channel.pipeline()) {
            handlerClassNames.add(entry.getValue().getClass().getSimpleName());
        }

        // containsSubsequence, not containsExactly: WebSocketServerProtocolHandler installs
        // its own internal helper handlers (handshake/UTF-8 validation) as an implementation
        // detail -- the AC is about the relative order of OUR stages, not Netty's internals.
        assertThat(handlerClassNames).containsSubsequence(
                "IpAdmissionHandler",
                "BackpressureHandler",
                "HttpServerCodec",
                "HttpObjectAggregator",
                "WebSocketServerProtocolHandler",
                "TicketAuthHandler",
                "RateLimitHandler",
                "GameMessageDecoder",
                "RoomRouteHandler");
        assertThat(channel.pipeline().get(SslHandler.class)).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_bindChannelAttributesAndRemoveItself_when_firstFrameIsValidJoinRoom() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);

        channel.writeInbound(frameOf(joinRoom("valid-ticket")));

        assertThat(channel.attr(ChannelAttributes.STUDENT_ID).get()).isEqualTo("student-1");
        assertThat(channel.attr(ChannelAttributes.ROOM_ID).get()).isEqualTo("room-1");
        assertThat(channel.attr(ChannelAttributes.SESSION_ID).get()).isEqualTo("session-1");
        assertThat(channel.pipeline().get(TicketAuthHandler.class)).isNull();
        assertThat(engineSender.last()).isNotNull();
    }

    @Test
    void should_closeChannel_when_ticketRejected() {
        TicketVerifier rejecting = ticket -> {
            throw new TicketRejectedException("expired");
        };
        EmbeddedChannel channel = applicationChannel(rejecting, new CapturingEngineSender());

        channel.writeInbound(frameOf(joinRoom("expired-ticket")));

        assertThat(channel.isOpen()).isFalse();
        assertThat(channel.attr(ChannelAttributes.ROOM_ID).get()).isNull();
    }

    @Test
    void should_closeChannel_when_firstFrameIsNotJoinRoom() {
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), new CapturingEngineSender());

        channel.writeInbound(frameOf(GameMessage.newBuilder().setType(MessageType.HEARTBEAT).build()));

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void should_closeChannel_when_payloadRoomIdDisagreesWithBoundRoomId() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-ticket")));
        engineSender.clear(); // drain the forwarded JOIN_ROOM

        GameMessage spoofed = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId("room-999") // a room this channel was never bound to
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(spoofed));

        assertThat(channel.isOpen()).isFalse();
        assertThat(engineSender.last()).as("a security event must never reach Engine").isNull();
    }

    @Test
    void should_closeChannel_when_payloadStudentIdDisagreesWithBoundStudentId() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-ticket")));
        engineSender.clear();

        GameMessage spoofed = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setStudentId("student-999") // not the student this channel authenticated as
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(spoofed));

        assertThat(channel.isOpen()).isFalse();
        assertThat(engineSender.last()).as("a spoofed student_id must never reach Engine").isNull();
    }

    @Test
    void should_forwardWithBoundRoomId_when_payloadRoomIdIsBlank() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-ticket")));
        engineSender.clear(); // drain the forwarded JOIN_ROOM

        GameMessage blank = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(blank));

        GameMessage forwarded = engineSender.last();
        assertThat(forwarded.getRoomId()).isEqualTo("room-1");
        assertThat(forwarded.getStudentId()).isEqualTo("student-1");
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void should_registerChannelInRoomRegistry_when_firstFrameIsValidJoinRoom() {
        RoomRegistry roomRegistry = new RoomRegistry();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), roomRegistry, new CapturingEngineSender());

        channel.writeInbound(frameOf(joinRoom("valid-ticket")));

        assertThat(roomRegistry.channelsIn("room-1")).contains(channel);
    }

    @Test
    void should_deregisterChannelFromRoomRegistry_when_channelGoesInactive() {
        RoomRegistry roomRegistry = new RoomRegistry();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), roomRegistry, new CapturingEngineSender());
        channel.writeInbound(frameOf(joinRoom("valid-ticket")));
        assertThat(roomRegistry.channelsIn("room-1")).contains(channel);

        channel.close();

        assertThat(roomRegistry.channelsIn("room-1")).doesNotContain(channel);
    }

    @Test
    void should_stampInternalHeaderTraceId_when_messageForwarded() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-ticket")));
        String traceId = engineSender.last().getInternal().getTraceId();
        assertThat(traceId).isNotBlank();

        GameMessage submit = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(submit));

        assertThat(engineSender.last().getInternal().getTraceId())
                .as("same connection must carry the same trace_id on every message, not a fresh one each time")
                .isEqualTo(traceId);
    }

    @Test
    void should_recordHandshake_when_joinSucceeds() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayMetrics gatewayMetrics = new GatewayMetrics(meterRegistry);
        RoomRegistry roomRegistry = new RoomRegistry();
        EmbeddedChannel first = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), roomRegistry, gatewayMetrics, new CapturingEngineSender());
        EmbeddedChannel second = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), roomRegistry, gatewayMetrics, new CapturingEngineSender());

        first.writeInbound(frameOf(joinRoom("valid-ticket-1")));
        second.writeInbound(frameOf(joinRoom("valid-ticket-2")));

        assertThat(meterRegistry.get("handshake_rate").counter().count()).isEqualTo(2.0);
    }

    private static EmbeddedChannel applicationChannel(TicketVerifier verifier, EngineSender engineSender) {
        return applicationChannel(verifier, new RoomRegistry(), new GatewayMetrics(new SimpleMeterRegistry()), engineSender);
    }

    private static EmbeddedChannel applicationChannel(
            TicketVerifier verifier, RoomRegistry roomRegistry, EngineSender engineSender) {
        return applicationChannel(verifier, roomRegistry, new GatewayMetrics(new SimpleMeterRegistry()), engineSender);
    }

    private static EmbeddedChannel applicationChannel(
            TicketVerifier verifier, RoomRegistry roomRegistry, GatewayMetrics gatewayMetrics, EngineSender engineSender) {
        return new EmbeddedChannel(
                new TicketAuthHandler(verifier, roomRegistry, gatewayMetrics),
                new RateLimitHandler(),
                new GameMessageDecoder(),
                new RoomRouteHandler(roomRegistry, engineSender));
    }

    private static TicketVerifier fixedVerifier(TicketClaims claims) {
        return ticket -> claims;
    }

    private static GameMessage joinRoom(String ticket) {
        return GameMessage.newBuilder()
                .setType(MessageType.JOIN_ROOM)
                .setJoinRoom(JoinRoom.newBuilder().setTicket(ticket))
                .build();
    }

    private static BinaryWebSocketFrame frameOf(GameMessage message) {
        return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message.toByteArray()));
    }

    /** Stands in for a real {@code FrameChannelClient} -- captures what would have been sent to Engine. */
    private static final class CapturingEngineSender implements EngineSender {
        private final List<GameMessage> sent = new ArrayList<>();

        @Override
        public void send(GameMessage message) {
            sent.add(message);
        }

        GameMessage last() {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }

        void clear() {
            sent.clear();
        }
    }
}
