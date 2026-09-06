package com.uni.realtime.engine.net;

import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-socket smoke test for Task 4's actual deliverable: a bindable internal frame channel
 * server. FrameCodecTest already proves the framing/protobuf rules with EmbeddedChannel; this
 * proves the two ends of a real TCP connection agree on the same wire format. Full multi-room,
 * multi-pod integration is Task 13's job, not this one's.
 */
class FrameChannelServerTest {

    @Test
    void should_deliverDecodedMessage_when_clientSendsFramedGameMessageOverRealSocket() throws Exception {
        BlockingQueue<GameMessage> received = new LinkedBlockingQueue<>();
        FrameChannelServer server = new FrameChannelServer(0, received::add);
        server.start();

        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            GameMessage sent = GameMessage.newBuilder()
                    .setType(MessageType.SUBMIT_ANSWER)
                    .setRoomId("room-9")
                    .setStudentId("student-3")
                    .setSequence(1L)
                    .build();

            Bootstrap client = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            for (ChannelHandler handler : FrameCodec.newHandlers()) {
                                ch.pipeline().addLast(handler);
                            }
                        }
                    });

            Channel clientChannel = client.connect("localhost", server.boundPort()).sync().channel();
            try {
                clientChannel.writeAndFlush(sent).sync();
                GameMessage decoded = received.poll(2, TimeUnit.SECONDS);
                assertThat(decoded).isEqualTo(sent);
            } finally {
                clientChannel.close().sync();
            }
        } finally {
            clientGroup.shutdownGracefully().sync();
            server.shutdown();
        }
    }
}
