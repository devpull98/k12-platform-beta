package com.uni.realtime.gateway.routing;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code room_id -> engine_pod_id}, learned lazily from {@code InternalHeader.owner_pod_id}
 * on Engine responses (§4.5, §8.2, decision PH-2). Deliberately no TTL: a stale entry is
 * corrected the next time it is used (a {@code NOT_OWNER} response re-learns it), and
 * {@link #evictPod} wipes entries immediately when a pod's connection drops. Nothing here
 * knows about {@code room_id % N} -- ownership is entirely Engine's {@code RoomOwnership}
 * (Task 10); a wrong guess here just costs one extra internal hop, never correctness.
 *
 * <p>Accessed from whichever Netty event-loop thread a given pod connection happens to run
 * on, so the map is a {@link ConcurrentHashMap} rather than assuming single-threaded access.
 */
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
