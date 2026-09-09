package com.uni.realtime.gameengine.net;

import com.uni.realtime.gameengine.room.AlwaysOwnRoomOwnership;
import com.uni.realtime.gameengine.room.RoomOwnership;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RoutingStatus;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10 verification: the wire-to-RoomActor trust boundary, with {@code EmbeddedChannel}
 * and no real socket (test-patterns.mdc). {@link RoomOwnership} is exercised as a trivial fixed
 * -answer stub here -- {@link LeaseBasedRoomOwnershipTest} in the {@code room} package already
 * covers the real (only) implementation's own logic in isolation.
 */
class RoomOwnershipHandlerTest {

    @Test
    void should_forwardToCallback_when_thisPodOwnsTheRoom() {
        RoomOwnership ownsEverything = new AlwaysOwnRoomOwnership("engine-solo");
        AtomicReference<GameMessage> forwarded = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new RoomOwnershipHandler(ownsEverything, forwarded::set));

        GameMessage owned = messageFor("room-1");
        channel.writeInbound(owned);

        assertThat(forwarded.get()).isEqualTo(owned);
        assertThat((GameMessage) channel.readOutbound()).isNull(); // no NOT_OWNER reply sent
    }

    @Test
    void should_replyNotOwnerWithRealOwner_when_thisPodDoesNotOwnTheRoom() {
        RoomOwnership ownedByAnotherPod = new RoomOwnership() {
            @Override
            public boolean isOwner(String roomId) {
                return false;
            }

            @Override
            public String ownerPodId(String roomId) {
                return "engine-b";
            }
        };
        AtomicReference<GameMessage> forwarded = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new RoomOwnershipHandler(ownedByAnotherPod, forwarded::set));

        channel.writeInbound(messageFor("room-1"));

        assertThat(forwarded.get()).as("a foreign room must never reach onOwnedMessage").isNull();
        GameMessage reply = channel.readOutbound();
        assertThat(reply.getRoomId()).isEqualTo("room-1");
        assertThat(reply.getInternal().getRoutingStatus()).isEqualTo(RoutingStatus.NOT_OWNER);
        assertThat(reply.getInternal().getOwnerPodId()).isEqualTo("engine-b");
    }

    @Test
    void should_dropSilently_when_ownerIsNotYetKnown() {
        // Task 14: a lease-based RoomOwnership can have a genuine "acquisition in flight"
        // window where neither isOwner nor a real ownerPodId is known yet.
        RoomOwnership unresolved = new RoomOwnership() {
            @Override
            public boolean isOwner(String roomId) {
                return false;
            }

            @Override
            public String ownerPodId(String roomId) {
                return "";
            }
        };
        AtomicReference<GameMessage> forwarded = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new RoomOwnershipHandler(unresolved, forwarded::set));

        channel.writeInbound(messageFor("room-pending"));

        assertThat(forwarded.get()).as("an unresolved room must never reach onOwnedMessage").isNull();
        assertThat((GameMessage) channel.readOutbound())
                .as("no NOT_OWNER reply -- a made-up owner would misroute the Gateway")
                .isNull();
    }

    @Test
    void should_callEnsureAcquired_beforeCheckingOwnership() {
        AtomicInteger ensureAcquiredCalls = new AtomicInteger();
        RoomOwnership counting = new RoomOwnership() {
            @Override
            public boolean isOwner(String roomId) {
                return true;
            }

            @Override
            public String ownerPodId(String roomId) {
                return "engine-solo";
            }

            @Override
            public void ensureAcquired(String roomId) {
                ensureAcquiredCalls.incrementAndGet();
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(new RoomOwnershipHandler(counting, message -> { }));

        channel.writeInbound(messageFor("room-1"));

        assertThat(ensureAcquiredCalls.get()).isEqualTo(1);
    }

    private static GameMessage messageFor(String roomId) {
        return GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId(roomId)
                .setStudentId("student-1")
                .setSequence(1L)
                .build();
    }
}
