package com.uni.realtime.gateway.net;

import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RejectReason;
import com.uni.realtime.protocol.SubmitAnswer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 verification (plan.md): per-student_id token buckets, EmbeddedChannel, no real
 * socket (test-patterns.mdc). The mandated case -- 500 clients behind one NAT IP all connect
 * fine -- is proven structurally: this handler never reads an IP anywhere, so 500 independent
 * instances (one per connection, matching GatewayPipeline) never contend with each other.
 */
class RateLimitHandlerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-06T09:00:00Z");

    @Test
    void should_allowSubmitAnswerUpToThreePerSecond_thenReplyRateLimitExceeded() {
        EmbeddedChannel channel = new EmbeddedChannel(new RateLimitHandler(fixedClock()));

        channel.writeInbound(submitAnswer(1));
        channel.writeInbound(submitAnswer(2));
        channel.writeInbound(submitAnswer(3));
        assertThat((GameMessage) channel.readInbound()).isNotNull();
        assertThat((GameMessage) channel.readInbound()).isNotNull();
        assertThat((GameMessage) channel.readInbound()).isNotNull();

        channel.writeInbound(submitAnswer(4));

        assertThat((GameMessage) channel.readInbound()).as("4th submit must not be forwarded").isNull();
        GameMessage reply = channel.readOutbound();
        assertThat(reply.getAnswerAck().getAccepted()).isFalse();
        assertThat(reply.getAnswerAck().getRejectReason()).isEqualTo(RejectReason.RATE_LIMIT_EXCEEDED);
        assertThat(reply.getAnswerAck().getAckedSequence()).isEqualTo(4);
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void should_dropUpdateDraftSilently_when_overTenPerTenSeconds() {
        EmbeddedChannel channel = new EmbeddedChannel(new RateLimitHandler(fixedClock()));
        for (int i = 1; i <= 10; i++) {
            channel.writeInbound(updateDraft(i));
            assertThat((GameMessage) channel.readInbound()).isNotNull();
        }

        channel.writeInbound(updateDraft(11));

        assertThat((GameMessage) channel.readInbound()).isNull();
        assertThat((GameMessage) channel.readOutbound()).as("no reply exists for UPDATE_DRAFT (§G3)").isNull();
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void should_dropHeartbeatSilently_when_overTwoPerThirtySeconds() {
        EmbeddedChannel channel = new EmbeddedChannel(new RateLimitHandler(fixedClock()));
        channel.writeInbound(heartbeat(1));
        channel.writeInbound(heartbeat(2));
        assertThat((GameMessage) channel.readInbound()).isNotNull();
        assertThat((GameMessage) channel.readInbound()).isNotNull();

        channel.writeInbound(heartbeat(3));

        assertThat((GameMessage) channel.readInbound()).isNull();
        assertThat((GameMessage) channel.readOutbound()).isNull();
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void should_notRateLimitMessageTypesOutsideTheThreeBuckets() {
        EmbeddedChannel channel = new EmbeddedChannel(new RateLimitHandler(fixedClock()));

        for (int i = 0; i < 20; i++) {
            channel.writeInbound(joinRoom());
            assertThat((GameMessage) channel.readInbound()).isNotNull();
        }
    }

    @Test
    void should_allowEveryClientIndependently_when_500ClientsShareOneNatIp() {
        // Real school scenario (§10.1): 500 students behind one IP, each their own connection
        // and therefore their own RateLimitHandler instance -- nothing here tracks IP at all.
        for (int i = 0; i < 500; i++) {
            EmbeddedChannel channel = new EmbeddedChannel(new RateLimitHandler(fixedClock()));

            channel.writeInbound(heartbeat(1));

            assertThat((GameMessage) channel.readInbound())
                    .as("client %d behind the shared IP must connect and send fine", i)
                    .isNotNull();
            channel.finishAndReleaseAll();
        }
    }

    private static Clock fixedClock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }

    private static GameMessage submitAnswer(long sequence) {
        return GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setStudentId("student-1")
                .setSequence(sequence)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-1"))
                .build();
    }

    private static GameMessage updateDraft(long sequence) {
        return GameMessage.newBuilder()
                .setType(MessageType.UPDATE_DRAFT)
                .setStudentId("student-1")
                .setSequence(sequence)
                .build();
    }

    private static GameMessage heartbeat(long sequence) {
        return GameMessage.newBuilder()
                .setType(MessageType.HEARTBEAT)
                .setStudentId("student-1")
                .setSequence(sequence)
                .build();
    }

    private static GameMessage joinRoom() {
        return GameMessage.newBuilder()
                .setType(MessageType.JOIN_ROOM)
                .setJoinRoom(JoinRoom.newBuilder().setTicket("tok"))
                .build();
    }
}
