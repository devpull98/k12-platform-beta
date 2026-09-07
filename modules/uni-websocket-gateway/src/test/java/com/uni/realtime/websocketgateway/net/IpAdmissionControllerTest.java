package com.uni.realtime.websocketgateway.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 verification (plan.md, §5.6 L1): the IP-keyed admission budget itself, independent of
 * Netty. {@code capacity}/{@code refillPeriod} are private constants (4.000/min, the Business
 * decision superseding plan.md's original 300) -- exercised here through the real public API,
 * not injected, so this test also acts as a change-detector on the threshold value itself.
 */
class IpAdmissionControllerTest {

    private static final int CAPACITY = 4_000;

    private MutableClock clock;
    private IpAdmissionController controller;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-06T09:00:00Z"));
        controller = new IpAdmissionController(clock);
    }

    @Test
    void should_admitUpToCapacity_thenReject_forTheSameIp() {
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(controller.tryAdmit("1.2.3.4")).isTrue();
        }

        assertThat(controller.tryAdmit("1.2.3.4")).isFalse();
    }

    @Test
    void should_trackBudgetsIndependently_perIp() {
        for (int i = 0; i < CAPACITY; i++) {
            controller.tryAdmit("1.2.3.4");
        }
        assertThat(controller.tryAdmit("1.2.3.4")).isFalse();

        // A school behind a different NAT IP must not pay for another school's excess -- the
        // same principle RateLimitHandler applies per-student_id, applied here per-IP.
        assertThat(controller.tryAdmit("5.6.7.8")).isTrue();
    }

    @Test
    void should_refillToFullCapacity_when_windowElapses() {
        for (int i = 0; i < CAPACITY; i++) {
            controller.tryAdmit("1.2.3.4");
        }
        assertThat(controller.tryAdmit("1.2.3.4")).isFalse();

        clock.advanceMinutes(1);

        assertThat(controller.tryAdmit("1.2.3.4")).isTrue();
    }

    @Test
    void should_neverAdmitMoreThanCapacity_when_concurrentConnectionsRaceFromTheSameIp() throws Exception {
        // The exact pattern L1 exists to catch: a burst of connections from one IP landing on
        // different Netty worker threads at once. The clock never advances here, so every
        // admit must come from the same fixed window -- any count over CAPACITY means
        // tryConsume()'s available-- raced instead of being properly serialized.
        int threadCount = 50;
        int attemptsPerThread = 200; // 10,000 total attempts against a 4,000 budget
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (controller.tryAdmit("1.2.3.4")) {
                            admitted.incrementAndGet();
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("threads did not finish in time").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get()).isEqualTo(CAPACITY);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceMinutes(long minutes) {
            instant = instant.plusSeconds(minutes * 60);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("not needed by this test double");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
