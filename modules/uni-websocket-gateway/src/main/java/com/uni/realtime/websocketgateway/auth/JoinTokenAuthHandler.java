package com.uni.realtime.websocketgateway.auth;

import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.net.ChannelAttributes;
import com.uni.realtime.websocketgateway.net.StudentHandshakeAdmissionController;
import com.uni.realtime.protocol.GameMessage;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class JoinTokenAuthHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(JoinTokenAuthHandler.class);

    private final JoinTokenVerifier joinTokenVerifier;
    private final RoomRegistry roomRegistry;
    private final GatewayMetrics gatewayMetrics;
    private final StudentHandshakeAdmissionController studentHandshakeAdmission;

    public JoinTokenAuthHandler(JoinTokenVerifier joinTokenVerifier, RoomRegistry roomRegistry, GatewayMetrics gatewayMetrics,
            StudentHandshakeAdmissionController studentHandshakeAdmission) {
        this.joinTokenVerifier = joinTokenVerifier;
        this.roomRegistry = roomRegistry;
        this.gatewayMetrics = gatewayMetrics;
        this.studentHandshakeAdmission = studentHandshakeAdmission;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        GameMessage message = GameMessage.parseFrom(new ByteBufInputStream(frame.content()));

        if (message.getPayloadCase() != GameMessage.PayloadCase.JOIN_ROOM) {
            log.warn("closing channel {}: first frame was {}, not JOIN_ROOM",
                    ctx.channel(), message.getPayloadCase());
            ctx.close();
            return;
        }

        JoinTokenClaims claims;
        try {
            claims = joinTokenVerifier.verify(message.getJoinRoom().getJoinToken());
        } catch (JoinTokenRejectedException e) {
            log.warn("closing channel {}: joinToken rejected ({})", ctx.channel(), e.getMessage());
            ctx.close();
            return;
        }

        if (!studentHandshakeAdmission.tryAdmit(claims.studentId())) {
            log.warn("closing channel {}: student {} exceeded L2 handshake admission control (§5.6)",
                    ctx.channel(), claims.studentId());
            ctx.close();
            return;
        }

        ChannelAttributes.bind(ctx.channel(), claims, UUID.randomUUID().toString());
        roomRegistry.add(claims.roomId(), claims.studentId(), ctx.channel());
        gatewayMetrics.recordHandshake();
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(message);
    }
}
