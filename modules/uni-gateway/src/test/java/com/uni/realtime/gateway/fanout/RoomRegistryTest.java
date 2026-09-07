package com.uni.realtime.gateway.fanout;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomRegistryTest {

    private final RoomRegistry registry = new RoomRegistry();

    @Test
    void should_returnEmptySet_when_roomHasNoChannels() {
        assertThat(registry.channelsIn("room-1")).isEmpty();
    }

    @Test
    void should_containChannel_when_added() {
        EmbeddedChannel channel = new EmbeddedChannel();

        registry.add("room-1", channel);

        assertThat(registry.channelsIn("room-1")).containsExactly(channel);
        channel.finishAndReleaseAll();
    }

    @Test
    void should_removeChannelFromEveryRoom_when_removed() {
        // A channel "shouldn't" be in two rooms in this architecture, but the AC says
        // "every set" -- the registry itself doesn't get to assume single-room membership.
        EmbeddedChannel channel = new EmbeddedChannel();
        registry.add("room-1", channel);
        registry.add("room-2", channel);

        registry.remove(channel);

        assertThat(registry.channelsIn("room-1")).isEmpty();
        assertThat(registry.channelsIn("room-2")).isEmpty();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_notAffectOtherChannels_when_oneChannelRemoved() {
        EmbeddedChannel staying = new EmbeddedChannel();
        EmbeddedChannel leaving = new EmbeddedChannel();
        registry.add("room-1", staying);
        registry.add("room-1", leaving);

        registry.remove(leaving);

        assertThat(registry.channelsIn("room-1")).containsExactly(staying);
        staying.finishAndReleaseAll();
        leaving.finishAndReleaseAll();
    }

    @Test
    void should_doNothing_when_removingAChannelNeverAdded() {
        EmbeddedChannel neverAdded = new EmbeddedChannel();

        registry.remove(neverAdded);

        assertThat(registry.channelsIn("room-1")).isEmpty();
        neverAdded.finishAndReleaseAll();
    }

    @Test
    void should_findChannelByStudentId_when_addedWithTheIndexedOverload() {
        EmbeddedChannel alice = new EmbeddedChannel();
        EmbeddedChannel bob = new EmbeddedChannel();
        registry.add("room-1", "student-alice", alice);
        registry.add("room-1", "student-bob", bob);

        assertThat(registry.channelFor("room-1", "student-bob")).contains(bob);
        alice.finishAndReleaseAll();
        bob.finishAndReleaseAll();
    }

    @Test
    void should_returnEmpty_when_noChannelIndexedForThatStudent() {
        assertThat(registry.channelFor("room-1", "student-nobody")).isEmpty();
    }

    @Test
    void should_removeFromStudentIndexToo_when_channelRemoved() {
        EmbeddedChannel alice = new EmbeddedChannel();
        registry.add("room-1", "student-alice", alice);

        registry.remove(alice);

        assertThat(registry.channelFor("room-1", "student-alice")).isEmpty();
        alice.finishAndReleaseAll();
    }
}
