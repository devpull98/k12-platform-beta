package com.uni.realtime.gateway.net;

import com.uni.realtime.gateway.fanout.Broadcaster;
import com.uni.realtime.gateway.fanout.RoomRegistry;
import com.uni.realtime.gateway.metrics.GatewayMetrics;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.DeliveryClass;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.InternalHeader;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RoomStateSnapshot;
import com.uni.realtime.protocol.RoutingStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    private EmbeddedChannel studentChannel(String roomId, String studentId) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(ChannelAttributes.STUDENT_ID).set(studentId);
        roomRegistry.add(roomId, channel);
        return channel;
    }

    private static GameMessage decode(EmbeddedChannel channel) throws Exception {
        BinaryWebSocketFrame frame = channel.readOutbound();
        if (frame == null) {
            return null;
        }
        try {
            return GameMessage.parseFrom(io.netty.buffer.ByteBufUtil.getBytes(frame.content()));
        } finally {
            frame.release();
        }
    }
}
