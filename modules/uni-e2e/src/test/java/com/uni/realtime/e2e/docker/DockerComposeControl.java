package com.uni.realtime.e2e.docker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Chaos-test support: no {@code ProcessBuilder}/Testcontainers wrapper around {@code docker
 * compose} existed anywhere in this repo before ({@code DockerComposeResyncIT}'s own javadoc
 * assumes the stack is already running, never starts/stops/kills a container from Java) --
 * {@code DockerComposeChaosIT} is the first test that needs to actually kill a container mid-run.
 *
 * <p>Every method shells out to the real {@code docker compose} CLI against
 * {@code docker-compose.dev.yml} at the repo root, resolved relative to this module's own
 * basedir ({@code modules/uni-e2e}, Surefire's default working directory) -- same assumption
 * {@code DockerComposeResyncIT}'s javadoc already documents for running these tests via
 * {@code mvn -pl :uni-e2e test}.
 */
final class DockerComposeControl {

    private static final Path COMPOSE_FILE = Path.of("..", "..", "docker-compose.dev.yml");

    private DockerComposeControl() {}

    /** {@code docker compose kill <service>} -- an abrupt SIGKILL, not a graceful stop (a real pod crash). */
    static void kill(String service) throws IOException, InterruptedException {
        run("kill", service);
    }

    /** {@code docker compose start <service>} -- restarts a killed container's SAME image/config, leaving the stack as found. */
    static void start(String service) throws IOException, InterruptedException {
        run("start", service);
    }

    /**
     * {@code docker compose up -d <service>} -- creates the container if it doesn't exist yet
     * (unlike {@link #start}, which requires one already created). This is the operation an SRE
     * actually runs to add a brand-new pod that was declared in the compose file but never
     * started -- see {@code DockerComposeScaleUpIT}.
     */
    static void up(String service) throws IOException, InterruptedException {
        run("up", "-d", service);
    }

    /** {@code docker compose stop <service>} -- graceful stop, container/state kept for a later {@link #start}. */
    static void stop(String service) throws IOException, InterruptedException {
        run("stop", service);
    }

    /**
     * {@code docker compose restart <service>} -- a fresh process, not just a fresh container
     * state. Needed for {@code gateway}: {@code FrameChannelClient} dials every Engine pod ONCE
     * at Gateway startup and never reconnects a pod it already marked dead (no retry loop exists
     * today) -- after a chaos test kills an Engine pod, the Gateway JVM that watched it die must
     * itself restart to re-dial the (now healthy again) Engine fleet, or every following test in
     * the same run only has one reachable pod left, regardless of how many Engine containers are
     * actually up.
     */
    static void restart(String service) throws IOException, InterruptedException {
        run("restart", service);
    }

    /** Polls {@code docker compose ps <service>} for a "healthy" status, same signal a human watching {@code ps} would use. */
    static void awaitHealthy(String service, java.time.Duration timeout) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            List<String> command = new ArrayList<>(List.of("docker", "compose", "-f", COMPOSE_FILE.toString(), "ps", service));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readAll(process);
            process.waitFor(10, TimeUnit.SECONDS);
            if (output.contains("healthy") && !output.contains("unhealthy") && !output.contains("starting")) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(service + " did not become healthy within " + timeout);
    }

    /**
     * Runs a command inside the given service's container (e.g. {@code valkey-cli GET
     * room:owner:<room_id>}) and returns trimmed stdout. Used to inspect real Hot
     * Snapshot/lease state in {@code room-store} without adding a Lettuce dependency to this
     * test module's classpath just for one-off assertions.
     */
    static String execCapture(String service, String... command) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("docker", "compose", "-f", COMPOSE_FILE.toString(),
                "exec", "-T", service));
        args.addAll(List.of(command));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = readAll(process);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException("docker compose exec " + service + " "
                    + String.join(" ", command) + " failed (exit=" + (finished ? process.exitValue() : "timeout")
                    + "): " + output);
        }
        return output.trim();
    }

    private static void run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-f", COMPOSE_FILE.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = readAll(process);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException("docker compose " + String.join(" ", args)
                    + " failed (exit=" + (finished ? process.exitValue() : "timeout") + "): " + output);
        }
    }

    private static String readAll(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }
}
