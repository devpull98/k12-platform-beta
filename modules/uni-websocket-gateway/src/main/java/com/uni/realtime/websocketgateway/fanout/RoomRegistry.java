package com.uni.realtime.websocketgateway.fanout;

import io.netty.channel.Channel;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomRegistry {

    private final Map<String, Set<Channel>> channelsByRoom = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Channel>> channelByRoomAndStudent = new ConcurrentHashMap<>();

    public void add(String roomId, Channel channel) {
        channelsByRoom.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    public void add(String roomId, String studentId, Channel channel) {
        add(roomId, channel);
        channelByRoomAndStudent.computeIfAbsent(roomId, ignored -> new ConcurrentHashMap<>()).put(studentId, channel);
    }

    public void remove(Channel channel) {
        channelsByRoom.values().forEach(channels -> channels.remove(channel));
        channelByRoomAndStudent.values().forEach(byStudent -> byStudent.values().remove(channel));
    }

    public Set<Channel> channelsIn(String roomId) {
        return channelsByRoom.getOrDefault(roomId, Set.of());
    }

    public Optional<Channel> channelFor(String roomId, String studentId) {
        return Optional.ofNullable(channelByRoomAndStudent.getOrDefault(roomId, Map.of()).get(studentId));
    }
}
