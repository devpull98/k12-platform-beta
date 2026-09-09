package com.uni.realtime.gameengine.room;

public interface RoomOwnership {

    boolean isOwner(String roomId);

    /**
     * The pod that owns this room, even when it isn't this one -- carried in
     * {@code InternalHeader.owner_pod_id} on a {@code NOT_OWNER} reply so the Gateway's
     * RouteCache can correct itself (§4.5, §8.2).
     *
     * <p>An empty string is the sentinel for "not known yet" -- only ever returned by an
     * implementation whose answer depends on something not yet resolved (a lease not yet
     * acquired). {@link ModuloRoomOwnership} never returns it: a pure function of
     * {@code (roomId, podCount)} is always immediately known. Callers (e.g.
     * {@code RoomOwnershipHandler}) must treat an empty owner as "drop this frame, do not
     * reply" rather than forwarding it as a real {@code owner_pod_id}.
     */
    String ownerPodId(String roomId);

    /**
     * Called once per incoming frame, before {@link #isOwner} / {@link #ownerPodId}, so an
     * implementation that must consult an external system to learn ownership (Task 14's
     * {@code LeaseBasedRoomOwnership}) gets a chance to kick that off. Must return immediately
     * and must never block the calling thread -- this runs on the Netty EventLoop via
     * {@code RoomOwnershipHandler} (ADR-005: no blocking I/O in an EventLoop), so an
     * implementation that needs a network round trip has to do it asynchronously and answer
     * {@link #isOwner}/{@link #ownerPodId} from a cache in the meantime.
     *
     * <p>No-op by default: an algorithm that decides ownership by pure computation (the
     * modulo of Phase 1's default) has nothing to acquire.
     */
    default void ensureAcquired(String roomId) {
        // Intentionally empty.
    }

    /**
     * The fencing generation this pod believes is current for {@code roomId} (Task 14, §5.8) --
     * stamped into a Hot Snapshot write so a zombie actor's stale write can be rejected. {@code 0}
     * by default: an algorithm with no notion of a lease (the modulo of Phase 1's default) has no
     * fencing to offer, and {@code 0} is already what {@code InternalHeader.epoch} means
     * "no fencing in effect" (ADR-007).
     */
    default long epochOf(String roomId) {
        return 0L;
    }
}
