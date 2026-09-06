package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.auth.TicketAuthHandler;
import com.uni.realtime.gateway.auth.TicketVerifier;
import com.uni.realtime.gateway.fanout.RoomRegistry;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Fixed handler order for the WebSocket edge (plan.md Task 6 AC) -- do not reorder.
 * {@code TicketAuthHandler} must run before anything that trusts {@link ChannelAttributes},
 * and {@code RoomRouteHandler} must run last so everything downstream already agrees on
 * identity and room ownership.
 *
 * <p>No {@code SslHandler} here, on purpose (ADR-008): TLS terminates at the LB/ingress and
 * this pod only ever sees plaintext WS. A config flag to enable TLS at the pod is not added
 * either -- an unused flag is an untested branch.
 */
public final class GatewayPipeline {

    public static final String WEBSOCKET_PATH = "/ws";
    private static final int MAX_HTTP_AGGREGATED_CONTENT_BYTES = 8 * 1024;

    private GatewayPipeline() {}

    public static void addTo(ChannelPipeline pipeline, TicketVerifier ticketVerifier, RoomRegistry roomRegistry) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_AGGREGATED_CONTENT_BYTES));
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
        pipeline.addLast(new TicketAuthHandler(ticketVerifier, roomRegistry));
        pipeline.addLast(new RateLimitHandler());
        pipeline.addLast(new GameMessageDecoder());
        pipeline.addLast(new RoomRouteHandler(roomRegistry));
    }
}
