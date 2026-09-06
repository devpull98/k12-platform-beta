package com.uni.realtime.gateway.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 verification (plan.md): pure logic, plain JUnit, no Netty (test-patterns.mdc).
 */
class RouteCacheTest {

    private final RouteCache cache = new RouteCache();

    @Test
    void should_returnEmpty_when_roomNeverLearned() {
        assertThat(cache.lookup("room-1")).isEmpty();
    }

    @Test
    void should_returnLearnedPod_when_roomWasLearnedBefore() {
        cache.learn("room-1", "engine-a");

        assertThat(cache.lookup("room-1")).contains("engine-a");
    }

    @Test
    void should_overwriteMapping_when_roomLearnedAgainWithDifferentPod() {
        // No TTL: re-learning is how a wrong entry corrects itself (§8.2), not an error case.
        cache.learn("room-1", "engine-a");

        cache.learn("room-1", "engine-b");

        assertThat(cache.lookup("room-1")).contains("engine-b");
    }

    @Test
    void should_removeEveryEntryForThatPod_when_podEvicted() {
        cache.learn("room-1", "engine-a");
        cache.learn("room-2", "engine-a");
        cache.learn("room-3", "engine-b");

        cache.evictPod("engine-a");

        assertThat(cache.lookup("room-1")).isEmpty();
        assertThat(cache.lookup("room-2")).isEmpty();
        assertThat(cache.lookup("room-3")).contains("engine-b"); // untouched pod's entries survive
    }

    @Test
    void should_doNothing_when_evictingAPodWithNoEntries() {
        cache.learn("room-1", "engine-a");

        cache.evictPod("engine-never-connected");

        assertThat(cache.lookup("room-1")).contains("engine-a");
    }
}
