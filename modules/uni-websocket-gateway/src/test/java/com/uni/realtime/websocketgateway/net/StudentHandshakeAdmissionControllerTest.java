package com.uni.realtime.websocketgateway.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.6 L2 verification: the student_id-keyed handshake budget itself, independent of Netty --
 * same shape as {@link IpAdmissionControllerTest} (L1), but keyed by the identity a ticket has
 * just verified rather than by IP, specifically so one student reconnecting too often never
 * exhausts a budget shared with classmates behind the same NAT IP (§5.6).
 */
class StudentHandshakeAdmissionControllerTest {

    private static final int CAPACITY = 10;

    private MutableClock clock;
    private StudentHandshakeAdmissionController controller;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-07T09:00:00Z"));
        controller = new StudentHandshakeAdmissionController(clock);
    }

    @Test
    void should_admitUpToCapacity_thenReject_forTheSameStudent() {
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(controller.tryAdmit("student-1")).isTrue();
        }

        assertThat(controller.tryAdmit("student-1")).isFalse();
    }

    @Test
    void should_trackBudgetsIndependently_perStudent() {
        for (int i = 0; i < CAPACITY; i++) {
            controller.tryAdmit("student-1");
        }
        assertThat(controller.tryAdmit("student-1")).isFalse();

        // A classmate behind the same NAT IP must not pay for one student's excess reconnects --
        // the whole reason this is keyed by student_id and not by IP (§5.6).
        assertThat(controller.tryAdmit("student-2")).isTrue();
    }

    @Test
    void should_refillToFullCapacity_when_windowElapses() {
        for (int i = 0; i < CAPACITY; i++) {
            controller.tryAdmit("student-1");
        }
        assertThat(controller.tryAdmit("student-1")).isFalse();

        clock.advanceMinutes(1);

        assertThat(controller.tryAdmit("student-1")).isTrue();
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
