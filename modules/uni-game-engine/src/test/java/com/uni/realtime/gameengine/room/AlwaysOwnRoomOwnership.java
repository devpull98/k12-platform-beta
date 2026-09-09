package com.uni.realtime.gameengine.room;

/**
 * Test fixture: claims ownership of every room for the given pod id. Used by tests that need
 * *some* {@link RoomOwnership} to wire a component together but aren't exercising ownership
 * logic itself -- {@link LeaseBasedRoomOwnershipTest} covers the real (only) implementation.
 */
public final class AlwaysOwnRoomOwnership implements RoomOwnership {

    private final String podId;

    public AlwaysOwnRoomOwnership(String podId) {
        this.podId = podId;
    }

    @Override
    public boolean isOwner(String roomId) {
        return true;
    }

    @Override
    public String ownerPodId(String roomId) {
        return podId;
    }
}
