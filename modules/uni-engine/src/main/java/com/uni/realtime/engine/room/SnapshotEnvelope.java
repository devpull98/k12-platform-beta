package com.uni.realtime.engine.room;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Task 14 (§5.8): wraps a {@link RoomState#serializeSnapshot()} payload with
 * {@code schema_version}, {@code epoch}, and a CRC32 -- the three guards system-architecture.md
 * §5.8 requires before trusting a byte blob pulled back out of Redis:
 * <ul>
 *   <li>{@code schema_version} -- refuses to read a snapshot written by older/incompatible code
 *       after a deploy, rather than misinterpreting its bytes.</li>
 *   <li>{@code epoch} -- the fencing token from {@link RedisLeaseRoomOwnership#epochOf}. Not
 *       enforced by this class (it has no notion of "current" epoch to compare against) --
 *       exposed via {@link Unwrapped#epoch()} so the caller (the pod about to resume a room)
 *       can reject a snapshot written by a zombie actor whose epoch is older than the lease it
 *       just won.</li>
 *   <li>{@code crc32} -- catches truncation/corruption in transit or at rest.</li>
 * </ul>
 *
 * <p>{@link #unwrap} never throws: schema mismatch, CRC mismatch, and a payload too short/
 * malformed to even read that far all resolve to {@link Optional#empty()}, matching §5.8 --
 * "Nếu snapshot lỗi: Coi như state rỗng và phục hồi hoàn toàn dựa trên client replay." A missing
 * snapshot and a corrupt one must look identical to the caller.
 */
final class SnapshotEnvelope {

    static final int SCHEMA_VERSION = 1;

    /**
     * Hard ceiling from system-architecture.md's Hot Snapshot budget. Enforced by
     * {@link #wrap} returning {@code Optional.empty()} rather than throwing: an oversized
     * snapshot must never crash the room (§5.8's spirit) -- the caller logs and skips that
     * write, trying again on the next flush.
     */
    static final int MAX_ENVELOPE_BYTES = 5 * 1024;

    private SnapshotEnvelope() {
    }

    record Unwrapped(long epoch, byte[] payload) {
    }

    static Optional<byte[]> wrap(long epoch, byte[] payload) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(SCHEMA_VERSION);
            out.writeLong(epoch);
            out.writeLong(crc32Of(payload));
            out.writeInt(payload.length);
            out.write(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] envelope = buffer.toByteArray();
        return envelope.length <= MAX_ENVELOPE_BYTES ? Optional.of(envelope) : Optional.empty();
    }

    static Optional<Unwrapped> unwrap(byte[] envelope) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(envelope))) {
            int schemaVersion = in.readInt();
            if (schemaVersion != SCHEMA_VERSION) {
                return Optional.empty();
            }
            long epoch = in.readLong();
            long expectedCrc32 = in.readLong();
            int payloadLength = in.readInt();
            byte[] payload = in.readNBytes(payloadLength);
            if (payload.length != payloadLength || crc32Of(payload) != expectedCrc32) {
                return Optional.empty();
            }
            return Optional.of(new Unwrapped(epoch, payload));
        } catch (IOException e) {
            // Too short to even contain a header, or some other read failure -- an untrustworthy
            // blob is exactly what this method exists to turn into "treat as empty" (§5.8),
            // not an exception the caller has to remember to catch.
            return Optional.empty();
        }
    }

    private static long crc32Of(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }
}
