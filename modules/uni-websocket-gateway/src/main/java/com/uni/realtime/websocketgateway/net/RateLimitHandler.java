package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RejectReason;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.time.Clock;
import java.time.Duration;

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
