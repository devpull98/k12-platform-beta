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

public final class GatewayPipeline {

    public static final String WEBSOCKET_PATH = "/ws";

    private static final int MAX_HTTP_AGGREGATED_CONTENT_BYTES = 50 * 1024;

    private GatewayPipeline() {}

    public static void addTo(ChannelPipeline pipeline, JoinTokenVerifier joinTokenVerifier, RoomRegistry roomRegistry,
            GatewayMetrics gatewayMetrics, IpAdmissionController ipAdmissionController,
            StudentHandshakeAdmissionController studentHandshakeAdmission, EngineSender engineSender) {
        pipeline.addLast(new IpAdmissionHandler(ipAdmissionController));
        pipeline.addLast(new BackpressureHandler(gatewayMetrics));
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_AGGREGATED_CONTENT_BYTES));
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
        pipeline.addLast(new JoinTokenAuthHandler(joinTokenVerifier, roomRegistry, gatewayMetrics, studentHandshakeAdmission));
        pipeline.addLast(new GameMessageDecoder());
        pipeline.addLast(new RateLimitHandler());
        pipeline.addLast(new RoomRouteHandler(roomRegistry, engineSender));
    }
}
