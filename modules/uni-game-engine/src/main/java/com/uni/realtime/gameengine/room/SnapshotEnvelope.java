package com.uni.realtime.gameengine.room;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.zip.CRC32;

final class SnapshotEnvelope {

    static final int SCHEMA_VERSION = 1;

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
            return Optional.empty();
        }
    }

    private static long crc32Of(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }
}
