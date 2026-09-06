package com.uni.realtime.engine.net;

import com.uni.realtime.engine.room.ModuloRoomOwnership;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RoutingStatus;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10 verification: the wire-to-RoomActor trust boundary, with {@code EmbeddedChannel}
 * and no real socket (test-patterns.mdc). {@link RoomOwnershipTest} already covers the
 * ownership algorithm itself in isolation; this covers what the handler does with the answer.
 */
class RoomOwnershipHandlerTest {

    private static final List<String> TWO_PODS = List.of("engine-a", "engine-b");

    @Test
    void should_forwardToCallback_when_thisPodOwnsTheRoom() {
        // engine-solo owns every room by construction (only pod in the list).
        ModuloRoomOwnership ownsEverything = new ModuloRoomOwnership("engine-solo", List.of("engine-solo"));
        AtomicReference<GameMessage> forwarded = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new RoomOwnershipHandler(ownsEverything, forwarded::set));

        GameMessage owned = messageFor("room-1");
        channel.writeInbound(owned);

        assertThat(forwarded.get()).isEqualTo(owned);
        assertThat((GameMessage) channel.readOutbound()).isNull(); // no NOT_OWNER reply sent
    }

    @Test
    void should_replyNotOwnerWithRealOwner_when_thisPodDoesNotOwnTheRoom() {
        // Find a room_id that hashes to the OTHER pod relative to "engine-a".
        ModuloRoomOwnership asSeenByA = new ModuloRoomOwnership("engine-a", TWO_PODS);
        String foreignRoomId = findRoomOwnedBy(asSeenByA, "engine-b");
        AtomicReference<GameMessage> forwarded = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new RoomOwnershipHandler(asSeenByA, forwarded::set));

        channel.writeInbound(messageFor(foreignRoomId));

        assertThat(forwarded.get()).as("a foreign room must never reach onOwnedMessage").isNull();
        GameMessage reply = channel.readOutbound();
        assertThat(reply.getRoomId()).isEqualTo(foreignRoomId);
        assertThat(reply.getInternal().getRoutingStatus()).isEqualTo(RoutingStatus.NOT_OWNER);
        assertThat(reply.getInternal().getOwnerPodId()).isEqualTo("engine-b");
    }

    private static GameMessage messageFor(String roomId) {
        return GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId(roomId)
                .setStudentId("student-1")
                .setSequence(1L)
                .build();
    }

    private static String findRoomOwnedBy(ModuloRoomOwnership ownership, String targetPodId) {
        for (int i = 0; i < 1000; i++) {
            String roomId = "room-" + i;
            if (ownership.ownerPodId(roomId).equals(targetPodId)) {
                return roomId;
            }
        }
        throw new IllegalStateException("no room in the first 1000 hashed to " + targetPodId);
    }
}
