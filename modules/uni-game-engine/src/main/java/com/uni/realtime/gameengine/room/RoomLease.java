package com.uni.realtime.gameengine.room;

/**
 * Task 14: who currently holds a room's ownership lease, and at which fencing generation.
 * {@code epoch} is what {@code InternalHeader.epoch} carries on the wire once
 * {@link LeaseBasedRoomOwnership} is in use -- it increments every time a NEW pod wins the
 * lease (never on a plain renewal by the same pod), so a snapshot write tagged with an older
 * epoch than the store currently holds is unambiguously from a zombie actor (§5.8).
 */
public record RoomLease(String podId, long epoch) {
}
