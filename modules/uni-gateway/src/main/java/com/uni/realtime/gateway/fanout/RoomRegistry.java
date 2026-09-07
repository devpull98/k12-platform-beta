package com.uni.realtime.gateway.fanout;

import io.netty.channel.Channel;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code room_id -> Set<Channel>} for exactly this Gateway pod (§4.4, ADR-006). Fan-out
 * ({@link Broadcaster}) reads only the set for the room being broadcast -- never filters a
 * global channel list -- which is the whole reason this map is shaped the way it is.
 *
 * <p>Backed by {@link ConcurrentHashMap}: a channel's {@code channelInactive} can fire on its
 * own event-loop thread while a broadcast triggered by a different pod connection iterates
 * the same room's set on another thread.
 */
public final class RoomRegistry {

    private final Map<String, Set<Channel>> channelsByRoom = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Channel>> channelByRoomAndStudent = new ConcurrentHashMap<>();

    public void add(String roomId, Channel channel) {
        channelsByRoom.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    /**
     * Also indexes by {@code student_id} so {@link #channelFor} is O(1) instead of scanning
     * every channel in the room -- worth it specifically because {@code ANSWER_ACK} looks this
     * up on every single answer submission (Task 13 review).
     */
    public void add(String roomId, String studentId, Channel channel) {
        add(roomId, channel);
        channelByRoomAndStudent.computeIfAbsent(roomId, ignored -> new ConcurrentHashMap<>()).put(studentId, channel);
    }

    /**
     * Must run the instant a channel dies (plan.md Task 8 AC) -- a dead {@link Channel} left
     * in a room's set is a cross-room data leak waiting to happen, not just a wasted write.
     * Removes from every room this channel might be in, not just one, since nothing here
     * tracks "the" room a channel belongs to -- that bookkeeping is the caller's.
     */
    public void remove(Channel channel) {
        channelsByRoom.values().forEach(channels -> channels.remove(channel));
        channelByRoomAndStudent.values().forEach(byStudent -> byStudent.values().remove(channel));
    }

    public Set<Channel> channelsIn(String roomId) {
        return channelsByRoom.getOrDefault(roomId, Set.of());
    }

    /** O(1) counterpart to scanning {@link #channelsIn} for one {@code student_id}. */
    public Optional<Channel> channelFor(String roomId, String studentId) {
        return Optional.ofNullable(channelByRoomAndStudent.getOrDefault(roomId, Map.of()).get(studentId));
    }
}
