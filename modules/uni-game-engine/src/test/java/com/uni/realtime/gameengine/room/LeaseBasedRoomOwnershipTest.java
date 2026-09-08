package com.uni.realtime.gameengine.room;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 verification: pure logic against a fake {@link RoomLeaseStore}, no real external store
 * (there is none available in this environment -- {@code DistributedRoomLeaseStore}, the real
 * Lettuce implementation, is exercised by nothing in this repo and must be verified against the
 * real cluster before staging/production, exactly like {@code JoinTokenAuthHandler}'s
 * placeholder verifier). Everything here is about the caching/async/fencing/fallback logic
 * this class owns, independent of which store backs it.
 */
class LeaseBasedRoomOwnershipTest {

    private static final Duration TTL = Duration.ofSeconds(20);

    @Test
    void should_answerUnknown_before_ensureAcquiredResolves() {
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", new NeverCompletingStore(), TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));

        assertThat(ownership.isOwner("room-1")).isFalse();
        assertThat(ownership.ownerPodId("room-1")).isEmpty();
    }

    @Test
    void should_becomeOwner_when_ensureAcquiredWinsTheLease() {
        FakeRoomLeaseStore store = FakeRoomLeaseStore.acquiredBy("engine-0", 1L);
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));

        ownership.ensureAcquired("room-1");

        assertThat(ownership.isOwner("room-1")).isTrue();
        assertThat(ownership.ownerPodId("room-1")).isEqualTo("engine-0");
        assertThat(ownership.epochOf("room-1")).isEqualTo(1L);
    }

    @Test
    void should_notOwn_when_anotherPodAlreadyHoldsTheLease() {
        FakeRoomLeaseStore store = FakeRoomLeaseStore.acquiredBy("engine-1", 3L);
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0", "engine-1")));

        ownership.ensureAcquired("room-1");

        assertThat(ownership.isOwner("room-1")).isFalse();
        assertThat(ownership.ownerPodId("room-1")).isEqualTo("engine-1");
    }

    @Test
    void should_callTheStoreExactlyOnce_when_ensureAcquiredIsCalledRepeatedlyAfterResolving() {
        FakeRoomLeaseStore store = FakeRoomLeaseStore.acquiredBy("engine-0", 1L);
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));

        ownership.ensureAcquired("room-1");
        ownership.ensureAcquired("room-1");
        ownership.ensureAcquired("room-1");

        assertThat(store.tryAcquireCalls.get()).as("cache hit must never re-touch the store").isEqualTo(1);
    }

    @Test
    void should_callTheStoreExactlyOnce_when_ensureAcquiredIsCalledConcurrentlyBeforeResolving() {
        // Simulates several frames for a brand-new room arriving before the first async
        // acquire resolves -- computeIfAbsent's atomicity is what this test actually proves.
        ControllableStore store = new ControllableStore();
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));

        ownership.ensureAcquired("room-1");
        ownership.ensureAcquired("room-1");
        ownership.ensureAcquired("room-1");
        assertThat(ownership.ownerPodId("room-1")).as("still unresolved").isEmpty();

        store.complete(new RoomLease("engine-0", 1L));

        assertThat(store.tryAcquireCalls.get()).isEqualTo(1);
        assertThat(ownership.isOwner("room-1")).isTrue();
    }

    @Test
    void should_fallBackToProvidedOwnership_when_theStoreFailsWithAnException() {
        RoomOwnership fallback = new ModuloRoomOwnership("engine-0", List.of("engine-0"));
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", new FailingStore(), TTL, fallback);

        ownership.ensureAcquired("room-1");

        assertThat(ownership.isOwner("room-1")).isEqualTo(fallback.isOwner("room-1"));
        assertThat(ownership.ownerPodId("room-1")).isEqualTo(fallback.ownerPodId("room-1"));
        assertThat(ownership.epochOf("room-1")).as("no fencing can be trusted on the fallback path").isZero();
    }

    @Test
    void should_allowRetrying_after_aFailedAcquireCompletes() {
        // The pending-marker must be cleared even on the exceptional path, or a room that hit
        // a transient store error would be stuck unresolvable forever.
        FailingStore failingOnce = new FailingStore();
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", failingOnce, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));
        ownership.ensureAcquired("room-1");
        assertThat(failingOnce.calls.get()).isEqualTo(1);

        // cache now holds the fallback's answer, so a second ensureAcquired is a cache hit --
        // proves the pending marker was removed rather than leaking forever, without needing a
        // second failure path.
        ownership.ensureAcquired("room-1");

        assertThat(failingOnce.calls.get()).as("cache hit, not a second attempt").isEqualTo(1);
    }

    @Test
    void should_keepOwnership_when_renewSucceeds() {
        FakeRoomLeaseStore store = FakeRoomLeaseStore.acquiredBy("engine-0", 1L);
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));
        ownership.ensureAcquired("room-1");
        store.renewResult = true;

        ownership.renewAll();

        assertThat(ownership.isOwner("room-1")).isTrue();
        assertThat(ownership.epochOf("room-1")).as("a plain renewal must not bump the epoch").isEqualTo(1L);
    }

    @Test
    void should_loseTheLease_when_renewReportsItWasNotRenewed() {
        FakeRoomLeaseStore store = FakeRoomLeaseStore.acquiredBy("engine-0", 1L);
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));
        ownership.ensureAcquired("room-1");
        store.renewResult = false;

        ownership.renewAll();

        assertThat(ownership.isOwner("room-1")).isFalse();
        assertThat(ownership.ownerPodId("room-1")).as("lost, not reassigned to anyone by this pod").isEmpty();
    }

    @Test
    void should_treatARenewException_asLostLease() {
        FakeRoomLeaseStore store = FakeRoomLeaseStore.acquiredBy("engine-0", 1L);
        store.renewThrows = true;
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0")));
        ownership.ensureAcquired("room-1");

        ownership.renewAll();

        assertThat(ownership.isOwner("room-1")).isFalse();
    }

    @Test
    void should_retryAcquisition_when_renewAllRunsAgain_forARoomOwnedByAnotherPod() {
        // Real-infra chaos test finding: without renewAll() evicting foreign-owned entries, this
        // pod would cache "engine-1 owns it" the FIRST time it lost the race and never retry --
        // even after engine-1's lease genuinely expired, since nothing else invalidates a
        // foreign-owned cache entry. ControllableStore (not the always-synchronous
        // FakeRoomLeaseStore) resolves each tryAcquire from OUTSIDE ensureAcquired's own call
        // stack, matching how a real, genuinely-async store completes -- a synchronously
        // completed future here would race pending.remove(roomId) against computeIfAbsent's own
        // insertion (a test-double-only artifact, never possible with a real network round trip).
        ControllableStore store = new ControllableStore();
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0", "engine-1")));
        ownership.ensureAcquired("room-1");
        store.complete(new RoomLease("engine-1", 5L));
        assertThat(ownership.ownerPodId("room-1")).isEqualTo("engine-1");

        // engine-1's lease has now expired server-side; this pod is the next to try.
        ownership.renewAll(); // PROVE-IT (2026-09-07): without the eviction, this call does nothing
        ownership.ensureAcquired("room-1");
        store.complete(new RoomLease("engine-0", 6L));

        assertThat(ownership.isOwner("room-1")).isTrue();
        assertThat(ownership.epochOf("room-1")).isEqualTo(6L);
        assertThat(store.tryAcquireCalls.get()).as("must have actually retried, not served a stale cache hit").isEqualTo(2);
    }

    @Test
    void should_neverRenewARoomOwnedByAnotherPod() {
        FakeRoomLeaseStore store = FakeRoomLeaseStore.acquiredBy("engine-1", 5L);
        LeaseBasedRoomOwnership ownership = new LeaseBasedRoomOwnership(
                "engine-0", store, TTL, new ModuloRoomOwnership("engine-0", List.of("engine-0", "engine-1")));
        ownership.ensureAcquired("room-1");

        ownership.renewAll();

        assertThat(store.renewCalls.get()).as("must not renew a lease this pod does not hold").isZero();
    }

    /** Always resolves to the given lease -- simulates this pod winning or another pod already owning it. */
    private static final class FakeRoomLeaseStore implements RoomLeaseStore {
        private final RoomLease acquireResult;
        private final AtomicInteger tryAcquireCalls = new AtomicInteger();
        private final AtomicInteger renewCalls = new AtomicInteger();
        private boolean renewResult = true;
        private boolean renewThrows = false;

        private FakeRoomLeaseStore(RoomLease acquireResult) {
            this.acquireResult = acquireResult;
        }

        static FakeRoomLeaseStore acquiredBy(String podId, long epoch) {
            return new FakeRoomLeaseStore(new RoomLease(podId, epoch));
        }

        @Override
        public CompletableFuture<RoomLease> tryAcquire(String roomId, String podId, Duration ttl) {
            tryAcquireCalls.incrementAndGet();
            return CompletableFuture.completedFuture(acquireResult);
        }

        @Override
        public CompletableFuture<Boolean> renew(String roomId, String podId, long epoch, Duration ttl) {
            renewCalls.incrementAndGet();
            if (renewThrows) {
                return CompletableFuture.failedFuture(new RuntimeException("simulated store error"));
            }
            return CompletableFuture.completedFuture(renewResult);
        }
    }

    /** Never resolves -- proves isOwner/ownerPodId answer "unknown" without waiting on anything. */
    private static final class NeverCompletingStore implements RoomLeaseStore {
        @Override
        public CompletableFuture<RoomLease> tryAcquire(String roomId, String podId, Duration ttl) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Boolean> renew(String roomId, String podId, long epoch, Duration ttl) {
            return new CompletableFuture<>();
        }
    }

    /** Lets the test complete the acquire future on its own schedule, after issuing several calls. */
    private static final class ControllableStore implements RoomLeaseStore {
        private final AtomicInteger tryAcquireCalls = new AtomicInteger();
        private CompletableFuture<RoomLease> inFlight;

        @Override
        public CompletableFuture<RoomLease> tryAcquire(String roomId, String podId, Duration ttl) {
            tryAcquireCalls.incrementAndGet();
            inFlight = new CompletableFuture<>();
            return inFlight;
        }

        @Override
        public CompletableFuture<Boolean> renew(String roomId, String podId, long epoch, Duration ttl) {
            return CompletableFuture.completedFuture(true);
        }

        void complete(RoomLease lease) {
            inFlight.complete(lease);
        }
    }

    private static final class FailingStore implements RoomLeaseStore {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<RoomLease> tryAcquire(String roomId, String podId, Duration ttl) {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new RuntimeException("simulated store error"));
        }

        @Override
        public CompletableFuture<Boolean> renew(String roomId, String podId, long epoch, Duration ttl) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
