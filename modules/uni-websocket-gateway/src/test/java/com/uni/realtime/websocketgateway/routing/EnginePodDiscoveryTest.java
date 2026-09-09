package com.uni.realtime.websocketgateway.routing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 21: proves {@link EnginePodDiscovery}'s polling/diffing logic in isolation, against fakes
 * -- no real sockets, no real Valkey. {@link ValkeyEnginePodResolver} itself follows the same
 * convention as {@code DistributedRoomLeaseStore} (Task 14): not unit-tested against a fake
 * Lettuce, verified instead by {@code DockerComposeScaleUpIT} against a real instance.
 */
class EnginePodDiscoveryTest {

    @Test
    void should_connectOnlyPodsNotAlreadyKnown() throws Exception {
        FakeConnector connector = new FakeConnector();
        connector.alreadyConnected.add("engine-0");
        FakeResolver resolver = new FakeResolver(List.of(
                new EnginePodAddress("engine-0", "host-0", 9100),
                new EnginePodAddress("engine-2", "host-2", 9100)));

        EnginePodDiscovery discovery = new EnginePodDiscovery(resolver, connector, Duration.ofMillis(20));
        try {
            discovery.start();
            connector.awaitAtLeast(1, Duration.ofSeconds(2));

            assertThat(connector.connectedCalls).containsExactly("engine-2");
        } finally {
            discovery.stop();
        }
    }

    @Test
    void should_keepPollingOtherPods_when_oneConnectAttemptFails() throws Exception {
        FakeConnector connector = new FakeConnector();
        connector.failFor.add("engine-broken");
        FakeResolver resolver = new FakeResolver(List.of(
                new EnginePodAddress("engine-broken", "host-broken", 9100),
                new EnginePodAddress("engine-2", "host-2", 9100)));

        EnginePodDiscovery discovery = new EnginePodDiscovery(resolver, connector, Duration.ofMillis(20));
        try {
            discovery.start();
            connector.awaitAtLeast(1, Duration.ofSeconds(2));

            assertThat(connector.connectedCalls).contains("engine-2");
            // Prove-it companion: engine-broken must keep being retried, not given up on after
            // one failure -- the resolver keeps returning it every cycle since it never actually
            // connects, so the fake's own attempt counter should climb past 1.
            assertThat(connector.attemptCountFor("engine-broken")).isGreaterThan(1);
        } finally {
            discovery.stop();
        }
    }

    @Test
    void should_notReconnect_when_podAlreadyConnectedByAnEarlierPoll() throws Exception {
        FakeConnector connector = new FakeConnector();
        FakeResolver resolver = new FakeResolver(List.of(new EnginePodAddress("engine-2", "host-2", 9100)));

        EnginePodDiscovery discovery = new EnginePodDiscovery(resolver, connector, Duration.ofMillis(20));
        try {
            discovery.start();
            connector.awaitAtLeast(1, Duration.ofSeconds(2));
            connector.alreadyConnected.add("engine-2"); // simulate the first connect() succeeding
            connector.connectedCalls.clear();

            Thread.sleep(100); // several more poll cycles at 20ms
            assertThat(connector.connectedCalls).isEmpty();
        } finally {
            discovery.stop();
        }
    }

    private static final class FakeResolver implements EnginePodResolver {
        private final List<EnginePodAddress> pods;

        FakeResolver(List<EnginePodAddress> pods) {
            this.pods = pods;
        }

        @Override
        public List<EnginePodAddress> resolve() {
            return pods;
        }
    }

    private static final class FakeConnector implements EngineConnector {
        final Set<String> alreadyConnected = new CopyOnWriteArraySet<>();
        final Set<String> failFor = new CopyOnWriteArraySet<>();
        final java.util.List<String> connectedCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.Map<String, AtomicInteger> attempts = new java.util.concurrent.ConcurrentHashMap<>();
        private final CountDownLatch anyCall = new CountDownLatch(1);

        @Override
        public boolean isConnected(String podId) {
            return alreadyConnected.contains(podId);
        }

        @Override
        public void connect(String podId, String host, int port) throws Exception {
            attempts.computeIfAbsent(podId, ignored -> new AtomicInteger()).incrementAndGet();
            anyCall.countDown();
            if (failFor.contains(podId)) {
                throw new java.net.ConnectException("simulated unreachable: " + podId);
            }
            // Real FrameChannelClient.connect() populates podChannels/knownPods synchronously
            // before returning -- mirror that so a successful connect() stops future polls from
            // reconnecting the same pod, exactly like production.
            alreadyConnected.add(podId);
            connectedCalls.add(podId);
        }

        int attemptCountFor(String podId) {
            return attempts.getOrDefault(podId, new AtomicInteger()).get();
        }

        void awaitAtLeast(int calls, Duration timeout) throws InterruptedException {
            assertThat(anyCall.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    .as("discovery never attempted a single connect() call")
                    .isTrue();
            // give a couple more poll cycles room to land the assertions below
            Thread.sleep(60);
        }
    }
}
