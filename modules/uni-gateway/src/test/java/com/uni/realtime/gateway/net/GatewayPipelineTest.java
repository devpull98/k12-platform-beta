package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.auth.TicketAuthHandler;
import com.uni.realtime.gateway.auth.TicketClaims;
import com.uni.realtime.gateway.auth.TicketRejectedException;
import com.uni.realtime.gateway.auth.TicketVerifier;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.SubmitAnswer;
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
 */
class GatewayPipelineTest {

    private static final TicketClaims ROOM_1_CLAIMS =
            new TicketClaims("student-1", "room-1", "session-1", List.of("student"));

    @Test
    void should_assembleHandlersInFixedOrderWithNoTlsHandler_when_pipelineBuilt() {
        EmbeddedChannel channel = new EmbeddedChannel();
        GatewayPipeline.addTo(channel.pipeline(), fixedVerifier(ROOM_1_CLAIMS));

        List<String> handlerClassNames = new ArrayList<>();
        for (Map.Entry<String, ChannelHandler> entry : channel.pipeline()) {
            handlerClassNames.add(entry.getValue().getClass().getSimpleName());
        }

        // containsSubsequence, not containsExactly: WebSocketServerProtocolHandler installs
        // its own internal helper handlers (handshake/UTF-8 validation) as an implementation
        // detail -- the AC is about the relative order of OUR stages, not Netty's internals.
        assertThat(handlerClassNames).containsSubsequence(
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
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS));

        channel.writeInbound(frameOf(joinRoom("valid-ticket")));

        assertThat(channel.attr(ChannelAttributes.STUDENT_ID).get()).isEqualTo("student-1");
        assertThat(channel.attr(ChannelAttributes.ROOM_ID).get()).isEqualTo("room-1");
        assertThat(channel.attr(ChannelAttributes.SESSION_ID).get()).isEqualTo("session-1");
        assertThat(channel.pipeline().get(TicketAuthHandler.class)).isNull();
        assertThat((GameMessage) channel.readInbound()).isNotNull();
    }

    @Test
    void should_closeChannel_when_ticketRejected() {
        TicketVerifier rejecting = ticket -> {
            throw new TicketRejectedException("expired");
        };
        EmbeddedChannel channel = applicationChannel(rejecting);

        channel.writeInbound(frameOf(joinRoom("expired-ticket")));

        assertThat(channel.isOpen()).isFalse();
        assertThat(channel.attr(ChannelAttributes.ROOM_ID).get()).isNull();
    }

    @Test
    void should_closeChannel_when_firstFrameIsNotJoinRoom() {
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS));

        channel.writeInbound(frameOf(GameMessage.newBuilder().setType(MessageType.HEARTBEAT).build()));

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void should_closeChannel_when_payloadRoomIdDisagreesWithBoundRoomId() {
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS));
        channel.writeInbound(frameOf(joinRoom("valid-ticket")));
        channel.readInbound(); // drain the forwarded JOIN_ROOM

        GameMessage spoofed = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId("room-999") // a room this channel was never bound to
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(spoofed));

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void should_forwardWithBoundRoomId_when_payloadRoomIdIsBlank() {
        EmbeddedChannel channel = applicationChannel(fixedVerifier(ROOM_1_CLAIMS));
        channel.writeInbound(frameOf(joinRoom("valid-ticket")));
        channel.readInbound(); // drain the forwarded JOIN_ROOM

        GameMessage blank = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
        channel.writeInbound(frameOf(blank));

        GameMessage forwarded = channel.readInbound();
        assertThat(forwarded.getRoomId()).isEqualTo("room-1");
        assertThat(channel.isOpen()).isTrue();
    }

    private static EmbeddedChannel applicationChannel(TicketVerifier verifier) {
        return new EmbeddedChannel(
                new TicketAuthHandler(verifier),
                new RateLimitHandler(),
                new GameMessageDecoder(),
                new RoomRouteHandler());
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
}
