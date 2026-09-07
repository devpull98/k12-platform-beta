package com.uni.realtime.websocketgateway.net;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 verification (plan.md, §5.6 L1): the Netty-facing side of admission control -- does the
 * handler actually read the connecting IP and act on {@link IpAdmissionController}'s verdict.
 * {@link IpAdmissionControllerTest} covers the counting logic itself; this only proves the wiring.
 *
 * <p>Plain {@code EmbeddedChannel} has no real socket, so {@code remoteAddress0()} normally
 * returns a fixed non-{@code InetSocketAddress} placeholder -- {@link FakeRemoteChannel} below
 * overrides it to a controllable {@code InetSocketAddress}, registering lazily (the
 * {@code (register=false, ...)} constructor) so the override's field is assigned before
 * {@code channelActive} can ever fire and read it.
 */
class IpAdmissionHandlerTest {

    @Test
    void should_keepChannelOpen_when_ipIsWithinBudget() throws Exception {
        FakeRemoteChannel channel = new FakeRemoteChannel("1.2.3.4", new IpAdmissionHandler(new IpAdmissionController()));

        channel.register();

        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void should_closeChannel_when_ipExceedsL1Budget() throws Exception {
        IpAdmissionController controller = new IpAdmissionController();
        for (int i = 0; i < 4_000; i++) {
            controller.tryAdmit("1.2.3.4");
        }
        FakeRemoteChannel channel = new FakeRemoteChannel("1.2.3.4", new IpAdmissionHandler(controller));

        channel.register();

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void should_notAffectAnotherIp_when_oneIpExceedsL1Budget() throws Exception {
        IpAdmissionController controller = new IpAdmissionController();
        for (int i = 0; i < 4_000; i++) {
            controller.tryAdmit("1.2.3.4");
        }

        // A school on a different NAT IP must still be able to connect -- the connection-time
        // counterpart to RateLimitHandler's "500 clients behind one IP" claim (Task 7).
        FakeRemoteChannel channel = new FakeRemoteChannel("5.6.7.8", new IpAdmissionHandler(controller));

        channel.register();

        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void should_notThrow_when_remoteAddressIsUnresolved() throws Exception {
        // InetSocketAddress.getAddress() returns null for an unresolved address -- shouldn't
        // happen for a real accepted connection, but this handler must fail safe, not NPE, if
        // it ever does (it is a security boundary, not a place to crash the event loop).
        FakeUnresolvedRemoteChannel channel =
                new FakeUnresolvedRemoteChannel(new IpAdmissionHandler(new IpAdmissionController()));

        channel.register();
        // EmbeddedChannel does not close on an unhandled exception -- it records it and only
        // surfaces it here, on demand. isOpen() alone would stay true either way.
        channel.checkException();

        assertThat(channel.isOpen()).isTrue();
    }

    private static final class FakeRemoteChannel extends EmbeddedChannel {
        private final InetSocketAddress remote;

        FakeRemoteChannel(String ip, ChannelHandler... handlers) {
            super(false, false, handlers); // register=false: assign `remote` before channelActive can read it
            this.remote = new InetSocketAddress(ip, 12345);
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return isActive() ? remote : null;
        }
    }

    private static final class FakeUnresolvedRemoteChannel extends EmbeddedChannel {
        private final InetSocketAddress remote = InetSocketAddress.createUnresolved("unresolved-host", 12345);

        FakeUnresolvedRemoteChannel(ChannelHandler... handlers) {
            super(false, false, handlers);
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return isActive() ? remote : null;
        }
    }
}
