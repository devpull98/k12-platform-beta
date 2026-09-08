package com.uni.realtime.e2e.support;

import com.uni.realtime.websocketgateway.net.GatewayPipeline;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.Resync;
import com.uni.realtime.protocol.SubmitAnswer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PH-3 stand-in (system-architecture.md §9.3): a real WebSocket client -- no {@code
 * EmbeddedChannel} anywhere -- extracted from {@code WalkingSkeletonTest}'s original {@code
 * WsTestClient} and enriched with the client-side contract that has never existed in this repo:
 * a bounded ring buffer of unacknowledged submissions, a monotonic client-assigned {@code
 * sequence}, and a real {@code RESYNC} sent on reconnect.
 *
 * <p>This does not claim to BE the production PH-3 client (§9.3's full spec also covers input
 * debounce, UI concerns, etc., none of which are this repo's to build) -- it exists so the
 * server-side {@code RoomActor.Resync} handling this task adds has something real to exercise it,
 * both in-process ({@code RoomActorResyncTest}) and against a real Docker stack ({@code
 * DockerComposeResyncIT}).
 */
public final class SimulatedStudentClient {

    /** §9.3: "RingBuffer 10 submission". */
    private static final int RING_BUFFER_CAPACITY = 10;

    private final int port;
    private final BlockingQueue<GameMessage> received;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final ArrayDeque<GameMessage> unacked = new ArrayDeque<>();
    private volatile long lastAckedSeq = 0;
    private EventLoopGroup group;
    private Channel channel;

    private SimulatedStudentClient(int port, BlockingQueue<GameMessage> received, EventLoopGroup group, Channel channel) {
        this.port = port;
        this.received = received;
        this.group = group;
        this.channel = channel;
    }

    public static SimulatedStudentClient connect(int port) throws InterruptedException {
        BlockingQueue<GameMessage> received = new LinkedBlockingQueue<>();
        ConnectedChannel connected = doConnect(port, received);
        return new SimulatedStudentClient(port, received, connected.group(), connected.channel());
    }

    /** Generic send -- unchanged from the original {@code WsTestClient}, used for JOIN_ROOM etc. */
    public void send(GameMessage message) {
        channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message.toByteArray())));
    }

    /**
     * Assigns the next monotonic client sequence (§9.3), buffers the submission (evicting the
     * oldest once past {@value #RING_BUFFER_CAPACITY}, matching a real ring buffer's overwrite
     * behavior), and sends it. Returns the assigned sequence so a caller can correlate the
     * resulting {@code ANSWER_ACK}.
     */
    public long submitTrackedAnswer(String roomId, String questionId, List<String> answerIds) {
        long sequence = sequenceCounter.incrementAndGet();
        GameMessage message = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId(roomId)
                .setSequence(sequence)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId(questionId).addAllAnswerIds(answerIds))
                .build();
        synchronized (unacked) {
            unacked.addLast(message);
            while (unacked.size() > RING_BUFFER_CAPACITY) {
                unacked.removeFirst();
            }
        }
        send(message);
        return sequence;
    }

    /**
     * Marks {@code sequence} as durably accounted for -- evicts it from the ring buffer and
     * advances {@code last_acked_seq} -- so a later {@link #simulateDisconnectAndReconnect} does
     * not resend it. A real client would call this on {@code ANSWER_ACK} (optimistic) or, per
     * this codebase's own {@code CommittedSeq} javadoc, only on {@code COMMITTED_SEQ} if it wants
     * the stronger "durably snapshotted" guarantee -- left to the caller to decide which signal
     * to drive this from, since that choice is exactly the one PH-3 has never made (see
     * {@code CommittedSeq}'s proto comment).
     */
    public void markAcked(long sequence) {
        synchronized (unacked) {
            unacked.removeIf(pending -> pending.getSequence() == sequence);
        }
        if (sequence > lastAckedSeq) {
            lastAckedSeq = sequence;
        }
    }

    /**
     * Simulates a network blip (§9.3's reconnect scenario): drops the connection with no
     * graceful WS close (a real disconnect does not warn anyone), reconnects to the same gateway
     * port, re-joins with a fresh ticket (a new connection always starts with JOIN_ROOM --
     * {@code TicketAuthHandler} requires it as the first frame), waits for the resulting personal
     * full snapshot, then sends a real {@code RESYNC} carrying every submission still sitting in
     * the ring buffer (i.e. never {@link #markAcked}) plus {@code last_acked_seq}.
     */
    public void simulateDisconnectAndReconnect(String roomId, String ticket, String displayName) throws InterruptedException {
        channel.close().sync();
        group.shutdownGracefully().sync();

        ConnectedChannel reconnected = doConnect(port, received);
        this.group = reconnected.group();
        this.channel = reconnected.channel();

        send(GameMessage.newBuilder()
                .setType(MessageType.JOIN_ROOM)
                .setJoinRoom(JoinRoom.newBuilder().setTicket(ticket).setDisplayName(displayName))
                .build());
        takeMatching("post-reconnect full snapshot",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());

        List<GameMessage> pending;
        synchronized (unacked) {
            pending = new ArrayList<>(unacked);
        }
        send(GameMessage.newBuilder()
                .setType(MessageType.RESYNC)
                .setRoomId(roomId)
                .setResync(Resync.newBuilder().setLastAckedSeq(lastAckedSeq).addAllPending(pending))
                .build());
    }

    /**
     * Test convenience: discards whatever is queued right now, no assertions. Used to settle a
     * client past unrelated earlier traffic (e.g. join-triggered deltas) before asserting
     * silence about a specific LATER window -- {@link #assertNoMoreMessagesFor} only proves "no
     * new message arrived", which is meaningless if stale ones were already sitting in the queue.
     */
    public void clearReceived() {
        received.clear();
    }

    public GameMessage takeMatching(String description, Predicate<GameMessage> predicate) throws InterruptedException {
        GameMessage message = pollMatching(3_000, predicate);
        if (message == null) {
            throw new AssertionError("expected " + description + " within 3s, none arrived");
        }
        return message;
    }

    /**
     * PH-3 stand-in for the client-side retry a first-touch multi-pod route miss relies on:
     * {@code RoomOwnershipHandler}'s own javadoc documents that Phase 1 deliberately does NOT
     * forward a misrouted frame internally between Engine pods (unlike what
     * system-architecture.md §4.5 step 2 literally says) -- "the client/Gateway retry cycle
     * already tolerates this" is the accepted mitigation instead. Discovered for real running
     * {@code DockerComposeResyncIT} against a genuinely multi-pod stack for the first time in
     * this repo's history (every earlier real-socket test used exactly one Engine pod, where a
     * route miss is structurally impossible). Resends JOIN_ROOM every {@code retryIntervalMillis}
     * until a full snapshot arrives or {@code maxAttempts} is exhausted.
     *
     * @return the matching full snapshot -- callers that need to inspect the restored roster
     *     (chaos tests) would otherwise have no way to see it: it is already drained off
     *     {@link #received} by the successful {@link #pollMatching} call below, so a caller
     *     that ignored this return value and tried {@link #takeMatching} again afterward would
     *     find nothing (this exact mistake is why this method used to return {@code void}).
     */
    public GameMessage joinRoomWithRetry(String ticket, String displayName, int maxAttempts, long retryIntervalMillis)
            throws InterruptedException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            send(GameMessage.newBuilder()
                    .setType(MessageType.JOIN_ROOM)
                    .setJoinRoom(JoinRoom.newBuilder().setTicket(ticket).setDisplayName(displayName))
                    .build());
            GameMessage reply = pollMatching(retryIntervalMillis,
                    m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
            if (reply != null) {
                return reply;
            }
        }
        throw new AssertionError("JOIN_ROOM got no full-snapshot reply after " + maxAttempts
                + " attempts (each waited " + retryIntervalMillis + "ms)");
    }

    private GameMessage pollMatching(long timeoutMillis, Predicate<GameMessage> predicate) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadlineNanos) {
            GameMessage message = received.poll(200, TimeUnit.MILLISECONDS);
            if (message != null && predicate.test(message)) {
                return message;
            }
        }
        return null;
    }

    /** Chaos-test support: proves a socket survived a degrade notice instead of only inferring it indirectly. */
    public boolean isOpen() {
        return channel.isOpen();
    }

    public void assertNoMoreMessagesFor(long millis) throws InterruptedException {
        GameMessage unexpected = received.poll(millis, TimeUnit.MILLISECONDS);
        assertThat(unexpected).as("expected silence but got %s", unexpected).isNull();
    }

    public void close() throws InterruptedException {
        channel.close().sync();
        group.shutdownGracefully().sync();
    }

    private record ConnectedChannel(EventLoopGroup group, Channel channel) {}

    private static ConnectedChannel doConnect(int port, BlockingQueue<GameMessage> received) throws InterruptedException {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                URI.create("ws://localhost:" + port + GatewayPipeline.WEBSOCKET_PATH),
                WebSocketVersion.V13, null, false, new DefaultHttpHeaders());
        CompletableFuture<Void> handshakeComplete = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new WebSocketClientProtocolHandler(handshaker));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (msg instanceof BinaryWebSocketFrame frame) {
                                    received.add(GameMessage.parseFrom(ByteBufUtil.getBytes(frame.content())));
                                }
                            }

                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                                    handshakeComplete.complete(null);
                                }
                            }
                        });
                    }
                });

        Channel channel = bootstrap.connect("localhost", port).sync().channel();
        try {
            handshakeComplete.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("WS handshake never completed", e);
        }
        return new ConnectedChannel(group, channel);
    }
}
