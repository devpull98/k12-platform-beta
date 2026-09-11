package com.uni.realtime.gameengine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Closes the "gap Task 11" gap documented throughout NOJIRA-uni-p1/p2 plan.md: the ONLY way a
 * real (non-test) join ever reaches {@link RoomSupervisor#spawnRoom} is with no
 * {@code GameDefinition} at all, because nothing tells the Engine what game a room should be
 * running. A CMS (or any other upstream owner of "what game is this class playing") provisions a
 * room by writing its {@code GameDefinition} (as JSON bytes, see
 * {@code com.uni.realtime.gameengine.provisioning}) here BEFORE the first student joins --
 * {@code RoomSupervisor} reads it back the first time it needs to spawn that {@code room_id},
 * exactly the same way it already reads back a Hot Snapshot for crash recovery.
 *
 * <p>Deliberately named by role, not by backing product (same reasoning as
 * {@link com.uni.realtime.gameengine.persistence.DistributedRoomLeaseStore}) -- see that class's
 * javadoc.
 */
public interface GameSessionDefinitionStore {

    /** Overwrites whatever was provisioned before for this room -- provisioning is idempotent by
     * design (a CMS retry or a teacher re-saving the same session must not be rejected). */
    CompletableFuture<Void> save(String roomId, byte[] definitionJsonBytes);

    /** Empty means "nothing provisioned" -- the caller falls back to a default SOLO room, it does
     * not distinguish "never provisioned" from "provisioned then expired/evicted". */
    CompletableFuture<Optional<byte[]>> load(String roomId);
}
