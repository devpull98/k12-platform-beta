package com.uni.realtime.websocketgateway.net;

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
 *
 * <p><b>Known unresolved deployment risk (Task 13 review):</b> {@code remoteAddress()} here is
 * whatever peer actually opened the TCP connection to this pod. If a Load Balancer or L7
 * ingress sits in front and proxies by terminating the client's TCP connection and opening a
 * new one to this pod (as opposed to an L4 passthrough that preserves the original source
 * address), every connection arrives looking like it came from the LB, not the real client --
 * L1 would then effectively become one global 4,000/min budget instead of a per-school one, or
 * silently pass every school through the LB's own bucket. Fixing this requires knowing the
 * actual ingress technology (PROXY protocol support, {@code X-Forwarded-For}, or an L4
 * passthrough config) -- none of which this codebase controls or has been told, the same kind
 * of external-team question G1a/G1c already is for the ticket format. Not fixed here; needs
 * confirmation from whoever owns the ingress before this control can be trusted in that
 * environment.
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
        // getAddress() is null for an unresolved InetSocketAddress -- shouldn't happen for a
        // real accepted server-side connection (you can't accept a TCP connection without
        // knowing the peer's IP), but this handler is a security boundary, so it fails safe
        // (falls back to the address's string form) instead of risking an NPE here.
        if (remote instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return String.valueOf(remote); // unresolved address, or no real socket (e.g. EmbeddedChannel in tests)
    }
}
