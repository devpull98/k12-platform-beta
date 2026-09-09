package com.uni.realtime.websocketgateway.routing;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RouteCache {

    private final Map<String, String> roomToPod = new ConcurrentHashMap<>();

    public Optional<String> lookup(String roomId) {
        return Optional.ofNullable(roomToPod.get(roomId));
    }

    public void learn(String roomId, String podId) {
        roomToPod.put(roomId, podId);
    }

    /**
     * A pod's connection dropped -- every room this cache thought lived there is now a guess.
     *
     * @return the room ids that were pointed at {@code podId} (Task 13, §9.7): whoever is
     *     connected to those rooms lost their route and needs {@code CONNECTION_DEGRADED},
     *     which this cache has no business knowing how to send.
     */
    public Set<String> evictPod(String podId) {
        Set<String> affectedRoomIds = new HashSet<>();
        roomToPod.forEach((roomId, pod) -> {
            if (pod.equals(podId)) {
                affectedRoomIds.add(roomId);
            }
        });
        roomToPod.values().removeIf(podId::equals);
        return affectedRoomIds;
    }
}
