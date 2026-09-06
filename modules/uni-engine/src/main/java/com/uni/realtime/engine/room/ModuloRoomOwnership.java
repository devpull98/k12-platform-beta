package com.uni.realtime.engine.room;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Phase 1 ownership rule (§7.3, ADR-007): {@code room_id.hashCode() % N} via modulo
 * arithmetic over a stable, sorted list of pod ids. This is the only file in the codebase
 * allowed to know that rule (plan.md Task 10 AC, grep-enforced).
 *
 * <p><b>Operational trap (system-architecture.md §9.2 Risk 4):</b> changing the pod count
 * reshuffles most rooms' ownership. Do not scale Engine pods while rooms are live under this
 * implementation -- that is exactly the rewrite Phase 2's ShardRegion-backed
 * {@code RoomOwnership} exists to remove.
 */
public final class ModuloRoomOwnership implements RoomOwnership {

    private final String selfPodId;
    private final List<String> podIdsSortedForStableIndexing;

    public ModuloRoomOwnership(String selfPodId, Collection<String> allPodIds) {
        this.selfPodId = selfPodId;
        this.podIdsSortedForStableIndexing = new ArrayList<>(allPodIds);
        podIdsSortedForStableIndexing.sort(String::compareTo);
        if (!podIdsSortedForStableIndexing.contains(selfPodId)) {
            throw new IllegalArgumentException("selfPodId '" + selfPodId + "' must be in allPodIds " + allPodIds);
        }
    }

    @Override
    public boolean isOwner(String roomId) {
        return selfPodId.equals(ownerPodId(roomId));
    }

    @Override
    public String ownerPodId(String roomId) {
        // Math.floorMod, not %: String.hashCode() can be negative, and a negative index into
        // podIdsSortedForStableIndexing would be a bug, not a valid pod.
        int index = Math.floorMod(roomId.hashCode(), podIdsSortedForStableIndexing.size());
        return podIdsSortedForStableIndexing.get(index);
    }
}
