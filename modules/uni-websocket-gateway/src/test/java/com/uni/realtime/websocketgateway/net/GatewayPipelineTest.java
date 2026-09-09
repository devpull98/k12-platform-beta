package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.auth.JoinTokenAuthHandler;
import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;
import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.routing.EngineSender;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RejectReason;
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
 * Task 6 verification (plan.md): fixed pipeline order, join-token-gated handshake, and the
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

    private static final JoinTokenClaims ROOM_1_CLAIMS =
            new JoinTokenClaims("student-1", "room-1", "session-1", List.of("student"));

    @Test
    void should_assembleHandlersInFixedOrderWithNoTlsHandler_when_pipelineBuilt() {
        EmbeddedChannel channel = new EmbeddedChannel();
        GatewayPipeline.addTo(channel.pipeline(), fixedVerifier(ROOM_1_CLAIMS), new RoomRegistry(),
                new GatewayMetrics(new SimpleMeterRegistry()), new IpAdmissionController(),
                new StudentHandshakeAdmissionController(), new CapturingEngineSender());

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
                "JoinTokenAuthHandler",
                "GameMessageDecoder",
                "RateLimitHandler",
                "RoomRouteHandler");
        assertThat(channel.pipeline().get(SslHandler.class)).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_bindChannelAttributesAndRemoveItself_when_firstFrameIsValidJoinRoom() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);

        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));

        assertThat(channel.attr(ChannelAttributes.STUDENT_ID).get()).isEqualTo("student-1");
        assertThat(channel.attr(ChannelAttributes.ROOM_ID).get()).isEqualTo("room-1");
        assertThat(channel.attr(ChannelAttributes.SESSION_ID).get()).isEqualTo("session-1");
        assertThat(channel.pipeline().get(JoinTokenAuthHandler.class)).isNull();
        assertThat(engineSender.last()).isNotNull();
    }

    @Test
    void should_closeChannel_when_joinTokenRejected() {
        JoinTokenVerifier rejecting = joinToken -> {
            throw new JoinTokenRejectedException("expired");
        };
        EmbeddedChannel channel = applicationChannel(rejecting, new CapturingEngineSender());

        channel.writeInbound(frameOf(joinRoom("expired-joinToken")));

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
        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));
        engineSender.clear(); // drain the forwarded JOIN_ROOM

        GameMessage spoofed = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId("room-999") // a room this channel was never bound to
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(spoofed));

        assertThat(channel.isOpen()).isFalse();
        // The forced close now also fires the leave-room flow (Engine must learn this student's
        // socket dropped) -- what must never reach Engine is the SPOOFED submission itself, not
        // literally nothing at all.
        assertThat(engineSender.last().getType())
                .as("the spoofed SUBMIT_ANSWER must never reach Engine")
                .isEqualTo(MessageType.STUDENT_LEFT);
    }

    @Test
    void should_closeChannel_when_payloadStudentIdDisagreesWithBoundStudentId() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));
        engineSender.clear();

        GameMessage spoofed = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setStudentId("student-999") // not the student this channel authenticated as
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(spoofed));

        assertThat(channel.isOpen()).isFalse();
        // Same reasoning as should_closeChannel_when_payloadRoomIdDisagreesWithBoundRoomId above.
        assertThat(engineSender.last().getType())
                .as("the spoofed student_id must never reach Engine")
                .isEqualTo(MessageType.STUDENT_LEFT);
    }

    @Test
    void should_forwardWithBoundRoomId_when_payloadRoomIdIsBlank() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));
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

        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));

        assertThat(roomRegistry.channelsIn("room-1")).contains(channel);
    }

    @Test
    void should_deregisterChannelFromRoomRegistry_when_channelGoesInactive() {
        RoomRegistry roomRegistry = new RoomRegistry();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), roomRegistry, new CapturingEngineSender());
        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));
        assertThat(roomRegistry.channelsIn("room-1")).contains(channel);

        channel.close();

        assertThat(roomRegistry.channelsIn("room-1")).doesNotContain(channel);
    }

    @Test
    void should_sendStudentLeft_when_aJoinedChannelGoesInactive() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));
        engineSender.clear(); // drain the forwarded JOIN_ROOM

        channel.close();

        GameMessage left = engineSender.last();
        assertThat(left).as("leave-room flow: Engine must learn a joined student's socket dropped").isNotNull();
        assertThat(left.getType()).isEqualTo(MessageType.STUDENT_LEFT);
        assertThat(left.getRoomId()).isEqualTo("room-1");
        assertThat(left.getStudentId()).isEqualTo("student-1");
    }

    @Test
    void should_notSendAnything_when_aNeverJoinedChannelGoesInactive() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        // No JOIN_ROOM ever sent on this channel -- ChannelAttributes were never bound.

        channel.close();

        assertThat(engineSender.last()).as("nothing to tell Engine about a channel that never joined anything").isNull();
    }

    @Test
    void should_stampInternalHeaderTraceId_when_messageForwarded() {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);
        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));
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
    void should_closeChannel_when_studentExceedsL2HandshakeAdmissionControl() {
        StudentHandshakeAdmissionController admission = new StudentHandshakeAdmissionController();
        for (int i = 0; i < 10; i++) {
            EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), new RoomRegistry(),
                    new GatewayMetrics(new SimpleMeterRegistry()), admission, new CapturingEngineSender());
            channel.writeInbound(frameOf(joinRoom("joinToken-" + i)));
            assertThat(channel.isOpen()).as("attempt %d must still be admitted", i).isTrue();
        }

        EmbeddedChannel eleventh = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), new RoomRegistry(),
                new GatewayMetrics(new SimpleMeterRegistry()), admission, new CapturingEngineSender());
        eleventh.writeInbound(frameOf(joinRoom("joinToken-11")));

        assertThat(eleventh.isOpen()).as("11th handshake from the same student within the window must be rejected").isFalse();
    }

    @Test
    void should_recordHandshake_when_joinSucceeds() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayMetrics gatewayMetrics = new GatewayMetrics(meterRegistry);
        RoomRegistry roomRegistry = new RoomRegistry();
        EmbeddedChannel first = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), roomRegistry, gatewayMetrics, new CapturingEngineSender());
        EmbeddedChannel second = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), roomRegistry, gatewayMetrics, new CapturingEngineSender());

        first.writeInbound(frameOf(joinRoom("valid-joinToken-1")));
        second.writeInbound(frameOf(joinRoom("valid-joinToken-2")));

        assertThat(meterRegistry.get("handshake_rate").counter().count()).isEqualTo(2.0);
    }

    @Test
    void should_rateLimitSubmitAnswer_when_binaryWebSocketFramesExceedBucket() throws Exception {
        CapturingEngineSender engineSender = new CapturingEngineSender();
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS), engineSender);

        // 1. Handshake & join room
        channel.writeInbound(frameOf(joinRoom("valid-joinToken")));
        engineSender.clear();

        // 2. SubmitAnswer bucket allows 3 requests/sec
        GameMessage submit = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setSequence(100L)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();

        // Send 3 allowed requests
        for (int i = 0; i < 3; i++) {
            channel.writeInbound(frameOf(submit));
        }
        assertThat(engineSender.sent.size()).as("first 3 SUBMIT_ANSWER requests must pass rate limiter").isEqualTo(3);

        // Send 4th request -> should be rate limited
        channel.writeInbound(frameOf(submit));
        assertThat(engineSender.sent.size()).as("4th SUBMIT_ANSWER request must be blocked by rate limiter").isEqualTo(3);

        // Verify outbound rate limit ACK message
        Object outbound = channel.readOutbound();
        assertThat(outbound).isInstanceOf(GameMessage.class);
        GameMessage ackMsg = (GameMessage) outbound;
        assertThat(ackMsg.getType()).isEqualTo(MessageType.ANSWER_ACK);
        assertThat(ackMsg.getRoomId()).isEqualTo("room-1");
        assertThat(ackMsg.getStudentId()).isEqualTo("student-1");

        AnswerAck answerAck = ackMsg.getAnswerAck();
        assertThat(answerAck.getAccepted()).isFalse();
        assertThat(answerAck.getRejectReason()).isEqualTo(RejectReason.RATE_LIMIT_EXCEEDED);
    }

    private static EmbeddedChannel applicationChannel(JoinTokenVerifier verifier, EngineSender engineSender) {
        return applicationChannel(verifier, new RoomRegistry(), new GatewayMetrics(new SimpleMeterRegistry()), engineSender);
    }

    private static EmbeddedChannel applicationChannel(
            JoinTokenVerifier verifier, RoomRegistry roomRegistry, EngineSender engineSender) {
        return applicationChannel(verifier, roomRegistry, new GatewayMetrics(new SimpleMeterRegistry()), engineSender);
    }

    private static EmbeddedChannel applicationChannel(
            JoinTokenVerifier verifier, RoomRegistry roomRegistry, GatewayMetrics gatewayMetrics, EngineSender engineSender) {
        return applicationChannel(verifier, roomRegistry, gatewayMetrics, new StudentHandshakeAdmissionController(), engineSender);
    }

    private static EmbeddedChannel applicationChannel(JoinTokenVerifier verifier, RoomRegistry roomRegistry,
            GatewayMetrics gatewayMetrics, StudentHandshakeAdmissionController studentHandshakeAdmission,
            EngineSender engineSender) {
        return new EmbeddedChannel(
                new JoinTokenAuthHandler(verifier, roomRegistry, gatewayMetrics, studentHandshakeAdmission),
                new GameMessageDecoder(),
                new RateLimitHandler(),
                new RoomRouteHandler(roomRegistry, engineSender));
    }

    private static JoinTokenVerifier fixedVerifier(JoinTokenClaims claims) {
        return joinToken -> claims;
    }

    private static GameMessage joinRoom(String joinToken) {
        return GameMessage.newBuilder()
                .setType(MessageType.JOIN_ROOM)
                .setJoinRoom(JoinRoom.newBuilder().setJoinToken(joinToken))
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
