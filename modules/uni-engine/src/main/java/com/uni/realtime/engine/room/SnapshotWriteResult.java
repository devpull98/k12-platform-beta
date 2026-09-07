package com.uni.realtime.engine.room;

/**
 * Task 14 follow-up (zombie-actor fix): {@link RoomSnapshotStore#save} used to return a plain
 * {@code boolean}, which could not distinguish two completely different reasons for
 * {@code false} -- {@link NoopRoomSnapshotStore} reporting "nothing persisted because
 * snapshotting is disabled" (Phase 1 default, entirely expected, happens on EVERY flush) versus
 * a real store reporting "rejected because another pod's epoch is newer" (a genuine, permanent
 * loss of ownership). Conflating them made "stop the actor when fenced" unsafe to implement: with
 * the boolean contract, doing so would have stopped every room immediately under today's default
 * config. This three-way result is what let {@link RoomActor#onLeaseLost} exist safely.
 */
public enum SnapshotWriteResult {
    /** Durably persisted. Safe to broadcast {@code CommittedSeq} (Task 15). */
    ACCEPTED,
    /** Rejected: the store's epoch counter is ahead of this write's epoch (§5.8). This pod no longer owns the room. */
    FENCED,
    /** This store does not actually persist anything (Phase 1 default). No claim either way -- not a failure. */
    DISABLED
}
