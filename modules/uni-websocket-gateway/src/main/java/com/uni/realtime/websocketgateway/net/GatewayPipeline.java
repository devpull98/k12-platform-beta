package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.auth.JoinTokenAuthHandler;
import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.routing.EngineSender;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Fixed handler order for the WebSocket edge (plan.md Task 6 AC) -- do not reorder.
 * {@code IpAdmissionHandler} (Task 7, §5.6 L1) goes first of all: an IP over budget must not
 * spend a cycle on anything downstream, including {@code BackpressureHandler}'s bookkeeping.
 * {@code BackpressureHandler} (Task 9) comes next since writability is a transport concern
 * unrelated to auth state. {@code JoinTokenAuthHandler} must run before anything that trusts
 * {@link ChannelAttributes}, and {@code RoomRouteHandler} must run last so everything
 * downstream already agrees on identity and room ownership.
 *
 * <p>No {@code SslHandler} here, on purpose (ADR-008): TLS terminates at the LB/ingress and
 * this pod only ever sees plaintext WS. A config flag to enable TLS at the pod is not added
 * either -- an unused flag is an untested branch.
 */
public final class GatewayPipeline {

    public static final String WEBSOCKET_PATH = "/ws";

    /** Product decision 2026-09-06 (system-architecture.md §1.1/§7.5): 50KB cap on every WS message. */
    private static final int MAX_HTTP_AGGREGATED_CONTENT_BYTES = 50 * 1024;

    private GatewayPipeline() {}

    public static void addTo(ChannelPipeline pipeline, JoinTokenVerifier joinTokenVerifier, RoomRegistry roomRegistry,
            GatewayMetrics gatewayMetrics, IpAdmissionController ipAdmissionController,
            StudentHandshakeAdmissionController studentHandshakeAdmission, EngineSender engineSender) {
        // Outermost gate: reject an over-budget IP before it costs this pod anything else.
        pipeline.addLast(new IpAdmissionHandler(ipAdmissionController));
        // Writability is a transport-level concern orthogonal to auth/decoding, and must
        // govern reads regardless of pipeline state.
        pipeline.addLast(new BackpressureHandler(gatewayMetrics));
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_AGGREGATED_CONTENT_BYTES));
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
        pipeline.addLast(new JoinTokenAuthHandler(joinTokenVerifier, roomRegistry, gatewayMetrics, studentHandshakeAdmission));
        pipeline.addLast(new RateLimitHandler());
        pipeline.addLast(new GameMessageDecoder());
        pipeline.addLast(new RoomRouteHandler(roomRegistry, engineSender));
    }
}
