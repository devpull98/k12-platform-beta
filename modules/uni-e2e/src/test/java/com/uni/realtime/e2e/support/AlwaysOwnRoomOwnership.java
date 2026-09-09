package com.uni.realtime.e2e.support;

import com.uni.realtime.gameengine.room.RoomOwnership;

/**
 * Test fixture: claims ownership of every room for the given pod id. These E2E tests spin up a
 * single Engine pod, so real lease acquisition (the only production {@link RoomOwnership}
 * implementation, {@code LeaseBasedRoomOwnership}) isn't what's under test here.
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
