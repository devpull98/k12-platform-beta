package com.uni.realtime.gameengine.room;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
        int index = Math.floorMod(roomId.hashCode(), podIdsSortedForStableIndexing.size());
        return podIdsSortedForStableIndexing.get(index);
    }
}
