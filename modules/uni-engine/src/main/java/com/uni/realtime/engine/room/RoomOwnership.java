package com.uni.realtime.engine.room;

/**
 * Task 10 / decision B2: which Engine pod owns a room, behind exactly one interface. Phase 2
 * replaces the single Phase-1 implementation with Cluster Sharding without touching
 * {@code FrameChannelServer}, the Gateway's {@code RouteCache}, or {@code RoomActor} --
 * nothing outside this interface's implementation may know how ownership is actually decided.
 */
public interface RoomOwnership {

    boolean isOwner(String roomId);

    /**
     * The pod that owns this room, even when it isn't this one -- carried in
     * {@code InternalHeader.owner_pod_id} on a {@code NOT_OWNER} reply so the Gateway's
     * RouteCache can correct itself (§4.5, §8.2).
     */
    String ownerPodId(String roomId);
}
