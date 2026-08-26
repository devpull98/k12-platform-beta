package com.ticketdd;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the DoD from SYSTEM_MONITORING_OBSERVABILITY_TECHNICAL_STANDARD.md
 * Mục VI / 19.1: cutting the MySQL connection must NOT restart the pod
 * (liveness stays up) — it must only pull the pod out of traffic (readiness
 * fails). No Kubernetes needed: these are plain Actuator HTTP endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HealthProbeTests {

    @LocalServerPort
    private int port;

    @Autowired
    private MySQLContainer<?> mysqlContainer;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void livenessIsUpAndDoesNotReportDatabase() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("\"db\"");
    }

    @Test
    void readinessIsUpAndReportsDatabaseWhileMysqlIsHealthy() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"db\"");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void readinessGoesDownWhenMysqlStopsButLivenessStaysUp() throws Exception {
        mysqlContainer.stop();

        HttpResponse<String> readiness = pollUntilStatus("/actuator/health/readiness", 503);
        assertThat(readiness.statusCode()).isEqualTo(503);

        HttpResponse<String> liveness = get("/actuator/health/liveness");
        assertThat(liveness.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> pollUntilStatus(String path, int expectedStatus) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        HttpResponse<String> last;
        do {
            last = get(path);
            if (last.statusCode() == expectedStatus) {
                return last;
            }
            Thread.sleep(200);
        } while (Instant.now().isBefore(deadline));
        return last;
    }

}
