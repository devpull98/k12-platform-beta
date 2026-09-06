package com.uni.realtime.gateway.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-06T09:00:00Z"));
    }

    @Test
    void should_allowUpToCapacity_withinTheSameWindow() {
        TokenBucket bucket = new TokenBucket(3, Duration.ofSeconds(1), clock);

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
    }

    @Test
    void should_rejectBeyondCapacity_withinTheSameWindow() {
        TokenBucket bucket = new TokenBucket(3, Duration.ofSeconds(1), clock);
        bucket.tryConsume();
        bucket.tryConsume();
        bucket.tryConsume();

        assertThat(bucket.tryConsume()).isFalse();
    }

    @Test
    void should_notRefill_when_windowHasNotYetElapsed() {
        TokenBucket bucket = new TokenBucket(1, Duration.ofSeconds(1), clock);
        bucket.tryConsume();

        clock.advanceMillis(999);

        assertThat(bucket.tryConsume()).isFalse();
    }

    @Test
    void should_refillToFullCapacity_when_windowElapses() {
        TokenBucket bucket = new TokenBucket(3, Duration.ofSeconds(1), clock);
        bucket.tryConsume();
        bucket.tryConsume();
        bucket.tryConsume();
        assertThat(bucket.tryConsume()).isFalse();

        clock.advanceMillis(1_000);

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
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
