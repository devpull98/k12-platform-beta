package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.fanout.Broadcaster;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.DeliveryClass;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RoomStateSnapshot;
import com.uni.realtime.protocol.RoutingStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 verification: the Gateway-side half of the wire that {@code FrameChannelClient}'s
 * {@code onResponse} calls into. Decoded from raw bytes with {@code GameMessage.parseFrom} to
 * assert on the actual wire content a client would see, not just that "something" was written.
 */
class EngineResponseRouterTest {

    private final RoomRegistry roomRegistry = new RoomRegistry();
    private final Broadcaster broadcaster = new Broadcaster(roomRegistry, new GatewayMetrics(new SimpleMeterRegistry()));
    private final EngineResponseRouter router = new EngineResponseRouter(roomRegistry, broadcaster);

    @Test
    void should_deliverAnswerAckOnlyToTheSubmittingStudent_when_othersShareTheRoom() throws Exception {
        EmbeddedChannel alice = studentChannel("room-1", "student-alice");
        EmbeddedChannel bob = studentChannel("room-1", "student-bob");

        router.route(GameMessage.newBuilder()
                .setType(MessageType.ANSWER_ACK)
                .setRoomId("room-1")
                .setStudentId("student-alice")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-1").setDeliveryClass(DeliveryClass.CRITICAL))
                .setAnswerAck(AnswerAck.newBuilder().setAccepted(true))
                .build());

        assertThat(decode(alice)).isNotNull();
        assertThat((BinaryWebSocketFrame) bob.readOutbound()).as("only the submitter gets their own ack").isNull();
    }

    @Test
    void should_broadcastToWholeRoom_when_roomStateSnapshot() throws Exception {
        EmbeddedChannel alice = studentChannel("room-2", "student-alice");
        EmbeddedChannel bob = studentChannel("room-2", "student-bob");

        router.route(GameMessage.newBuilder()
                .setType(MessageType.ROOM_STATE_SNAPSHOT)
                .setRoomId("room-2")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-1").setDeliveryClass(DeliveryClass.BEST_EFFORT))
                .setRoomStateSnapshot(RoomStateSnapshot.newBuilder().setFull(true))
                .build());

        assertThat(decode(alice)).isNotNull();
        assertThat(decode(bob)).isNotNull();
    }

    @Test
    void should_closeABackpressuredChannel_when_personalMessageIsCritical() {
        EmbeddedChannel alice = studentChannel("room-9", "student-alice");
        alice.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));
        alice.write(Unpooled.wrappedBuffer(new byte[1000]));
        assertThat(alice.isWritable()).as("test setup: channel must actually be backpressured").isFalse();

        router.route(GameMessage.newBuilder()
                .setType(MessageType.ANSWER_ACK)
                .setRoomId("room-9")
                .setStudentId("student-alice")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-1").setDeliveryClass(DeliveryClass.CRITICAL))
                .setAnswerAck(AnswerAck.newBuilder().setAccepted(true))
                .build());

        assertThat(alice.isOpen())
                .as("a personal CRITICAL message must obey the same backpressure rule as a broadcast one (§5.4/§10.2)")
                .isFalse();
    }

    @Test
    void should_deliverOnlyToTheJoiner_when_roomStateSnapshotCarriesAStudentId() throws Exception {
        // RoomActor.onJoinRoom stamps its personal full-snapshot reply with the joiner's
        // student_id specifically so this router does not mistake it for a room broadcast.
        EmbeddedChannel alice = studentChannel("room-7", "student-alice");
        EmbeddedChannel bob = studentChannel("room-7", "student-bob");

        router.route(GameMessage.newBuilder()
                .setType(MessageType.ROOM_STATE_SNAPSHOT)
                .setRoomId("room-7")
                .setStudentId("student-bob")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-1").setDeliveryClass(DeliveryClass.BEST_EFFORT))
                .setRoomStateSnapshot(RoomStateSnapshot.newBuilder().setFull(true))
                .build());

        assertThat(decode(bob)).as("the joiner must receive their own full snapshot").isNotNull();
        assertThat((BinaryWebSocketFrame) alice.readOutbound())
                .as("a personal join reply must not reach every other student in the room")
                .isNull();
    }

    @Test
    void should_stripInternalHeader_beforeReachingAClient() throws Exception {
        EmbeddedChannel alice = studentChannel("room-3", "student-alice");

        router.route(GameMessage.newBuilder()
                .setType(MessageType.ROOM_STATE_SNAPSHOT)
                .setRoomId("room-3")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-1").setTraceId("trace-xyz"))
                .setRoomStateSnapshot(RoomStateSnapshot.newBuilder().setFull(true))
                .build());

        assertThat(decode(alice).hasInternal()).isFalse();
    }

    @Test
    void should_deliverNothing_when_responseIsNotOwner() {
        EmbeddedChannel alice = studentChannel("room-4", "student-alice");

        router.route(GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId("room-4")
                .setStudentId("student-alice")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-2").setRoutingStatus(RoutingStatus.NOT_OWNER))
                .build());

        assertThat((BinaryWebSocketFrame) alice.readOutbound())
                .as("a NOT_OWNER reply carries no client payload and must never be delivered")
                .isNull();
    }

    @Test
    void should_broadcastConnectionDegraded_toEveryChannelInEachAffectedRoom() throws Exception {
        EmbeddedChannel alice = studentChannel("room-5", "student-alice");
        EmbeddedChannel bob = studentChannel("room-6", "student-bob");

        router.broadcastConnectionDegraded(Set.of("room-5", "room-6"));

        assertThat(decode(alice).getType()).isEqualTo(MessageType.CONNECTION_DEGRADED);
        assertThat(decode(bob).getType()).isEqualTo(MessageType.CONNECTION_DEGRADED);
        assertThat(alice.isOpen()).as("§9.7: degrade, never close, the client's own WebSocket").isTrue();
    }

    @Test
    void should_notCloseABackpressuredChannel_when_broadcastingConnectionDegraded() {
        EmbeddedChannel alice = studentChannel("room-8", "student-alice");
        // Same technique as BackpressureTest (Task 9): an unflushed pending write trips the
        // high watermark, making the channel genuinely !isWritable() without a real socket.
        alice.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2));
        alice.write(Unpooled.wrappedBuffer(new byte[1000]));
        assertThat(alice.isWritable()).as("test setup: channel must actually be backpressured").isFalse();

        router.broadcastConnectionDegraded(Set.of("room-8"));

        assertThat(alice.isOpen())
                .as("§9.7: a backed-up client must not have its WebSocket closed by a degrade notice")
                .isTrue();
    }

    @Test
    void should_deliverStudentKickedNoticeThenCloseTheChannel_regardlessOfWritability() throws Exception {
        EmbeddedChannel alice = studentChannel("room-10", "student-alice");

        router.route(GameMessage.newBuilder()
                .setType(MessageType.STUDENT_KICKED)
                .setRoomId("room-10")
                .setStudentId("student-alice")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-1").setDeliveryClass(DeliveryClass.CRITICAL))
                .build());

        assertThat(decode(alice).getType()).isEqualTo(MessageType.STUDENT_KICKED);
        assertThat(alice.isOpen())
                .as("unlike ANSWER_ACK/CONNECTION_DEGRADED, a kick notice must close the socket right after delivery")
                .isFalse();
    }

    @Test
    void should_releaseTheFrame_when_kickedStudentHasNoChannelOnThisPod() {
        // Engine fans STUDENT_KICKED out to every subscribed Gateway pod (§B1) -- only the one
        // actually holding the student's channel should do anything with it.
        studentChannel("room-11", "student-someone-else");

        router.route(GameMessage.newBuilder()
                .setType(MessageType.STUDENT_KICKED)
                .setRoomId("room-11")
                .setStudentId("student-not-on-this-pod")
                .setInternal(InternalHeader.newBuilder().setOwnerPodId("engine-1").setDeliveryClass(DeliveryClass.CRITICAL))
                .build());
        // No assertion beyond "did not throw" -- paranoid leak detection (Surefire-wide) is what
        // actually proves the frame was released, not left dangling.
    }

    private EmbeddedChannel studentChannel(String roomId, String studentId) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(ChannelAttributes.STUDENT_ID).set(studentId);
        roomRegistry.add(roomId, studentId, channel);
        return channel;
    }

    private static GameMessage decode(EmbeddedChannel channel) throws Exception {
        BinaryWebSocketFrame frame = channel.readOutbound();
        if (frame == null) {
            return null;
        }
        try {
            byte[] wireBytes = io.netty.buffer.ByteBufUtil.getBytes(frame.content());
            return GameMessage.parseFrom(WireCompression.decode(wireBytes));
        } finally {
            frame.release();
        }
    }
}
