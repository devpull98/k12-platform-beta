package com.uni.realtime.gameengine.room;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface RoomSnapshotStore {

    /**
     * @return {@link SnapshotWriteResult#ACCEPTED} if the write went through,
     *     {@link SnapshotWriteResult#FENCED} if the store rejected it because {@code epoch} is
     *     older than the room's current epoch (§5.8's fencing: a zombie actor that lost its
     *     lease must not be able to overwrite a newer pod's state -- {@code RoomActor} stops
     *     itself on this, see {@code onLeaseLost}), or {@link SnapshotWriteResult#DISABLED} if
     *     this store does not actually persist anything ({@code NoopRoomSnapshotStore}, Phase 1
     *     default) -- deliberately a separate case from {@code FENCED} so a disabled store can
     *     never be mistaken for a lost lease.
     */
    CompletableFuture<SnapshotWriteResult> save(String roomId, long epoch, byte[] envelopeBytes);

    /** Empty means "nothing stored, or unreadable" -- the caller cannot and need not tell the two apart. */
    CompletableFuture<Optional<byte[]>> load(String roomId);
}
