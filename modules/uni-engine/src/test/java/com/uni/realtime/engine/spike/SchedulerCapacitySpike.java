package com.uni.realtime.engine.spike;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SPIKE harness (plan.md, time-boxed, not a regression test): does Pekko's default
 * hashed-wheel scheduler hold accuracy and stay cheap on CPU with ~1,000 concurrent
 * single-shot timers per pod at 200ms resolution (ADR-4)?
 *
 * <p>Deliberately NOT named {@code *Test} so Surefire's default include pattern skips it --
 * this is a manual, one-off measurement run, not part of the regular build. See
 * {@code docs/work/NOJIRA-uni-p1-realtime-core/spike-pekko-timer.md} for the recorded result
 * and decision.
 *
 * <p>Run with (from repo root):
 * <pre>
 * mvn -pl :uni-engine test-compile
 * mvn -pl :uni-engine dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -XX:ActiveProcessorCount=2 -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *     com.uni.realtime.engine.spike.SchedulerCapacitySpike
 * </pre>
 */
public final class SchedulerCapacitySpike {

    private static final int ACTOR_COUNT = 1000;
    private static final long TICK_MS = 200;
    private static final long RUN_SECONDS = 20;

    public static void main(String[] args) throws Exception {
        ConcurrentLinkedQueue<Long> deviationsMs = new ConcurrentLinkedQueue<>();
        long endAtNanos = System.nanoTime() + Duration.ofSeconds(RUN_SECONDS).toNanos();
        Random random = new Random();

        Behavior<Void> root = Behaviors.setup(context -> {
            for (int i = 0; i < ACTOR_COUNT; i++) {
                long initialDelayMs = (long) (random.nextDouble() * TICK_MS);
                context.spawn(DirtyRoomActor.create(deviationsMs, initialDelayMs, TICK_MS, endAtNanos), "room-" + i);
            }
            return Behaviors.empty();
        });

        System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors());
        ActorSystem<Void> system = ActorSystem.create(root, "spike-scheduler-capacity");

        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        osBean.getProcessCpuLoad(); // first call is often -1; warm it up before sampling

        List<Double> cpuSamples = new ArrayList<>();
        while (System.nanoTime() < endAtNanos) {
            Thread.sleep(500);
            double load = osBean.getProcessCpuLoad();
            if (load >= 0) {
                cpuSamples.add(load);
            }
        }
        // Let in-flight timers finish landing their last sample before shutting down.
        Thread.sleep(TICK_MS * 2);
        system.terminate();

        List<Long> sorted = new ArrayList<>(deviationsMs);
        Collections.sort(sorted);
        double avgCpu = cpuSamples.stream().mapToDouble(d -> d).average().orElse(-1);
        double maxCpu = cpuSamples.stream().mapToDouble(d -> d).max().orElse(-1);

        System.out.println("=== SPIKE RESULT ===");
        System.out.println("actorCount=" + ACTOR_COUNT + " tickMs=" + TICK_MS + " runSeconds=" + RUN_SECONDS);
        System.out.println("sampleCount=" + sorted.size());
        System.out.println("p50DeviationMs=" + percentile(sorted, 50));
        System.out.println("p90DeviationMs=" + percentile(sorted, 90));
        System.out.println("p99DeviationMs=" + percentile(sorted, 99));
        System.out.println("maxDeviationMs=" + (sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1)));
        System.out.println("avgCpuLoad=" + String.format("%.3f", avgCpu));
        System.out.println("maxCpuLoad=" + String.format("%.3f", maxCpu));
    }

    private static long percentile(List<Long> sortedAscending, int pct) {
        if (sortedAscending.isEmpty()) {
            return -1;
        }
        int index = (int) Math.ceil(pct / 100.0 * sortedAscending.size()) - 1;
        return sortedAscending.get(Math.max(0, Math.min(index, sortedAscending.size() - 1)));
    }

    private sealed interface Command {}

    private record FireTick(long scheduledAtNanos, long requestedDelayMs) implements Command {}

    /**
     * Mimics ADR-4's actual mechanism: one single-shot timer per cycle, rescheduled after it
     * fires -- not a periodic {@code startTimerAtFixedRate}, which would hide scheduling drift
     * behind the wheel's own periodic correction.
     */
    private static final class DirtyRoomActor {

        static Behavior<Command> create(
                ConcurrentLinkedQueue<Long> deviationsMs, long initialDelayMs, long tickMs, long endAtNanos) {
            return Behaviors.withTimers(timers ->
                    scheduleNext(timers, deviationsMs, tickMs, endAtNanos, initialDelayMs));
        }

        private static Behavior<Command> scheduleNext(
                TimerScheduler<Command> timers, ConcurrentLinkedQueue<Long> deviationsMs,
                long tickMs, long endAtNanos, long delayMs) {
            long scheduledAtNanos = System.nanoTime();
            timers.startSingleTimer(new FireTick(scheduledAtNanos, delayMs), Duration.ofMillis(delayMs));

            return Behaviors.receive(Command.class)
                    .onMessage(FireTick.class, msg -> {
                        long nowNanos = System.nanoTime();
                        long actualDelayMs = (nowNanos - msg.scheduledAtNanos()) / 1_000_000;
                        deviationsMs.add(actualDelayMs - msg.requestedDelayMs());

                        if (nowNanos >= endAtNanos) {
                            return Behaviors.stopped();
                        }
                        return scheduleNext(timers, deviationsMs, tickMs, endAtNanos, tickMs);
                    })
                    .build();
        }
    }
}
