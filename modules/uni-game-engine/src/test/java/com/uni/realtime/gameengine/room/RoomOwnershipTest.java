package com.uni.realtime.gameengine.room;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 10 verification (plan.md): pure logic, plain JUnit, no Netty (test-patterns.mdc).
 */
class RoomOwnershipTest {

    private static final List<String> THREE_PODS = List.of("engine-a", "engine-b", "engine-c");

    @Test
    void should_agreeWithSelf_when_isOwnerAndOwnerPodIdBothQueried() {
        ModuloRoomOwnership ownership = new ModuloRoomOwnership("engine-a", THREE_PODS);

        boolean isOwner = ownership.isOwner("room-101");
        String ownerPodId = ownership.ownerPodId("room-101");

        assertThat(isOwner).isEqualTo(ownerPodId.equals("engine-a"));
    }

    @Test
    void should_returnSameOwner_regardlessOfWhichPodInstanceAsks() {
        // The algorithm must not accidentally depend on "self" beyond the isOwner comparison --
        // every pod in the cluster has to compute the same answer for the same room.
        ModuloRoomOwnership asSeenByA = new ModuloRoomOwnership("engine-a", THREE_PODS);
        ModuloRoomOwnership asSeenByB = new ModuloRoomOwnership("engine-b", THREE_PODS);

        assertThat(asSeenByA.ownerPodId("room-101")).isEqualTo(asSeenByB.ownerPodId("room-101"));
        assertThat(asSeenByA.ownerPodId("room-999")).isEqualTo(asSeenByB.ownerPodId("room-999"));
    }

    @Test
    void should_throw_when_selfPodIdIsNotInAllPodIds() {
        assertThatThrownBy(() -> new ModuloRoomOwnership("engine-ghost", THREE_PODS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_ownEveryRoom_when_onlyOnePodExists() {
        ModuloRoomOwnership onlyPod = new ModuloRoomOwnership("engine-solo", List.of("engine-solo"));

        for (int i = 0; i < 50; i++) {
            assertThat(onlyPod.isOwner("room-" + i)).isTrue();
        }
    }

    @Test
    void should_distributeRoomsAcrossAllPods_when_manyRoomsHashed() {
        // Not a claim about balance, just that the hash isn't degenerate to a single bucket.
        ModuloRoomOwnership ownership = new ModuloRoomOwnership("engine-a", THREE_PODS);
        Set<String> ownersSeen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            ownersSeen.add(ownership.ownerPodId("room-" + i));
        }

        assertThat(ownersSeen).containsExactlyInAnyOrderElementsOf(THREE_PODS);
    }
}
