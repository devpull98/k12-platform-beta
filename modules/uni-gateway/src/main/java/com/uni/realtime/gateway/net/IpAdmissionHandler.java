package com.uni.realtime.gateway.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * L1 admission control (system-architecture.md §5.6): the outermost gate in {@link
 * GatewayPipeline}, ahead of everything else, so an IP over budget never spends a CPU cycle on
 * HTTP parsing, the WS upgrade, or ticket verification. Runs at {@link #channelActive} -- each
 * new TCP connection to this pod counts as one handshake attempt against {@link
 * IpAdmissionController}, which is the cheapest point available to reject at, before any of the
 * real handshake work happens.
 */
public final class IpAdmissionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(IpAdmissionHandler.class);

    private final IpAdmissionController controller;

    public IpAdmissionHandler(IpAdmissionController controller) {
        this.controller = controller;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String ip = remoteIp(ctx);
        if (controller.tryAdmit(ip)) {
            super.channelActive(ctx);
        } else {
            log.warn("closing channel {}: IP {} exceeded L1 admission control (§5.6)", ctx.channel(), ip);
            ctx.close();
        }
    }

    private static String remoteIp(ChannelHandlerContext ctx) {
        SocketAddress remote = ctx.channel().remoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return String.valueOf(remote); // no real socket (e.g. EmbeddedChannel in tests)
    }
}
