package com.uni.realtime.gateway.net;

import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RejectReason;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.time.Clock;
import java.time.Duration;

/**
 * Per-connection token buckets keyed conceptually by {@code student_id} (§5.6, §10.1) --
 * {@link GatewayPipeline} builds a fresh handler instance per channel, and a channel belongs
 * to exactly one student once {@code TicketAuthHandler} has bound it, so no shared map across
 * connections is needed here.
 *
 * <p>Deliberately never keyed by IP: a school behind one NAT IP can put 500+ students on it,
 * and IP-based limiting would throttle all of them for one student's excess (plan.md Task 7's
 * mandated test). L1's IP-based admission control (300 handshake/min, §5.6) is a connection-time
 * concern for the ingress/handshake layer, not this per-message handler -- it is not
 * implemented here.
 *
 * <p>Only {@code SUBMIT_ANSWER}'s rejection can actually ride the wire: {@code AnswerAck}'s
 * {@code reject_reason} is the only field the schema has for this. {@code UPDATE_DRAFT} and
 * {@code HEARTBEAT} have no ack payload at all ({@code UPDATE_DRAFT}'s doesn't even exist yet
 * in the oneof -- tech-design.md §G3), so an over-limit message of either type is dropped
 * silently; the channel is never closed either way.
 */
public final class RateLimitHandler extends SimpleChannelInboundHandler<GameMessage> {

    private final TokenBucket submitAnswerBucket;
    private final TokenBucket updateDraftBucket;
    private final TokenBucket heartbeatBucket;

    public RateLimitHandler() {
        this(Clock.systemUTC());
    }

    RateLimitHandler(Clock clock) {
        this.submitAnswerBucket = new TokenBucket(3, Duration.ofSeconds(1), clock);
        this.updateDraftBucket = new TokenBucket(10, Duration.ofSeconds(10), clock);
        this.heartbeatBucket = new TokenBucket(2, Duration.ofSeconds(30), clock);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GameMessage message) {
        switch (message.getType()) {
            case SUBMIT_ANSWER -> {
                if (submitAnswerBucket.tryConsume()) {
                    ctx.fireChannelRead(message);
                } else {
                    ctx.writeAndFlush(rateLimitedAck(message));
                }
            }
            case UPDATE_DRAFT -> {
                if (updateDraftBucket.tryConsume()) {
                    ctx.fireChannelRead(message);
                }
                // else: dropped -- no wire vehicle exists yet for this type's rejection (§G3).
            }
            case HEARTBEAT -> {
                if (heartbeatBucket.tryConsume()) {
                    ctx.fireChannelRead(message);
                }
                // else: dropped -- HEARTBEAT has no ack in the schema at all.
            }
            default -> ctx.fireChannelRead(message); // not rate-limited by this task's AC
        }
    }

    private static GameMessage rateLimitedAck(GameMessage message) {
        return GameMessage.newBuilder()
                .setType(MessageType.ANSWER_ACK)
                .setRoomId(message.getRoomId())
                .setStudentId(message.getStudentId())
                .setSequence(message.getSequence())
                .setAnswerAck(AnswerAck.newBuilder()
                        .setQuestionId(message.getSubmitAnswer().getQuestionId())
                        .setAckedSequence(message.getSequence())
                        .setAccepted(false)
                        .setRejectReason(RejectReason.RATE_LIMIT_EXCEEDED))
                .build();
    }
}
