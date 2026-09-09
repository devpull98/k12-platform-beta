package com.uni.realtime.websocketgateway.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

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
        if (remote instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return String.valueOf(remote);
    }
}
