package com.uni.realtime.engine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Task 14: where a {@link RoomActor} persists its Hot Snapshot. Async by contract for the same
 * reason as {@link RoomLeaseStore} -- callers must never block waiting on this (§13.2, ADR-005),
 * and {@code RoomActor}'s caller in particular must never let a slow/unavailable store delay
 * {@code ANSWER_ACK} or any other hot-path reply.
 */
public interface RoomSnapshotStore {

    /**
     * Resolves {@code true} if the write went through, {@code false} if the store rejected it
     * because {@code epoch} is older than the room's current epoch (§5.8's fencing: a zombie
     * actor that lost its lease must not be able to overwrite a newer pod's state). A
     * {@code false} result is a strong signal the calling {@code RoomActor} no longer holds the
     * lease it thinks it does -- reacting to that (e.g. stopping itself) is a follow-up to this
     * interface, not part of it.
     */
    CompletableFuture<Boolean> save(String roomId, long epoch, byte[] envelopeBytes);

    /** Empty means "nothing stored, or unreadable" -- the caller cannot and need not tell the two apart. */
    CompletableFuture<Optional<byte[]>> load(String roomId);
}
