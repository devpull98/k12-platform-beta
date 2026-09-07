package com.uni.realtime.engine.room;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 14 (§5.8) verification: pure logic, no external store, no actor. */
class SnapshotEnvelopeTest {

    @Test
    void should_roundTripEpochAndPayload() {
        byte[] payload = "hello room state".getBytes(StandardCharsets.UTF_8);

        Optional<byte[]> wrapped = SnapshotEnvelope.wrap(7L, payload);
        Optional<SnapshotEnvelope.Unwrapped> unwrapped = wrapped.flatMap(SnapshotEnvelope::unwrap);

        assertThat(unwrapped).isPresent();
        assertThat(unwrapped.get().epoch()).isEqualTo(7L);
        assertThat(unwrapped.get().payload()).isEqualTo(payload);
    }

    @Test
    void should_returnEmpty_when_crc32DoesNotMatch() {
        byte[] payload = "hello room state".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = SnapshotEnvelope.wrap(1L, payload).orElseThrow();
        byte[] corrupted = wrapped.clone();
        corrupted[corrupted.length - 1] ^= 0x01; // flip a bit inside the payload

        Optional<SnapshotEnvelope.Unwrapped> unwrapped = SnapshotEnvelope.unwrap(corrupted);

        assertThat(unwrapped).as("corrupt payload must be treated as empty state, never crash (§5.8)").isEmpty();
    }

    @Test
    void should_returnEmpty_when_schemaVersionDoesNotMatch() {
        byte[] wrapped = SnapshotEnvelope.wrap(1L, "payload".getBytes(StandardCharsets.UTF_8)).orElseThrow();
        wrapped[3] = (byte) (wrapped[3] + 1); // last byte of the big-endian schema_version int

        assertThat(SnapshotEnvelope.unwrap(wrapped)).isEmpty();
    }

    @Test
    void should_returnEmpty_when_bytesAreTooShortToBeAnEnvelopeAtAll() {
        assertThat(SnapshotEnvelope.unwrap(new byte[] {1, 2, 3})).isEmpty();
    }

    @Test
    void should_returnEmpty_when_envelopeExceedsTheFiveKilobyteBudget() {
        byte[] tooLarge = new byte[6 * 1024];

        assertThat(SnapshotEnvelope.wrap(1L, tooLarge))
                .as("an oversized snapshot must be refused, not silently written past the budget")
                .isEmpty();
    }

    @Test
    void should_acceptExactlyAtTheBudgetBoundary() {
        // MAX_ENVELOPE_BYTES accounts for the envelope header itself, not just the raw payload.
        int headerBytes = SnapshotEnvelope.wrap(1L, new byte[0]).orElseThrow().length;
        byte[] payload = new byte[SnapshotEnvelope.MAX_ENVELOPE_BYTES - headerBytes];

        assertThat(SnapshotEnvelope.wrap(1L, payload)).isPresent();
    }
}
