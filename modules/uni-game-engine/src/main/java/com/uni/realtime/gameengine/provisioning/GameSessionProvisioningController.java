package com.uni.realtime.gameengine.provisioning;

import com.uni.realtime.gameengine.boot.EngineNetworkLifecycle;
import com.uni.realtime.gameengine.definition.DefinitionRejectedException;
import com.uni.realtime.gameengine.definition.GameSessionDefinitionMapper;
import com.uni.realtime.gameengine.definition.GameSessionDefinitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CMS-facing provisioning endpoint (see docs/specs/tech-design/cms-game-session-provisioning.md
 * for the full contract). Runs on the management/Tomcat side (8090), NOT the Netty frame-channel
 * hot path -- this is a low-frequency, ahead-of-time write (once per class session scheduled),
 * never called from the per-message realtime path, so a blocking-looking call here does not
 * violate the "no I/O on the Netty EventLoop" rule (system-architecture.md §13.2).
 *
 * <p><b>No auth on this endpoint yet</b> -- it MUST be reachable only from CMS's own network
 * segment (K8s NetworkPolicy), never exposed publicly, until an explicit decision is made about
 * service-to-service auth (same "not production-ready without more work" caveat this repo already
 * uses elsewhere, e.g. {@code AlwaysAcceptJoinTokenVerifier}).
 */
@RestController
public class GameSessionProvisioningController {

    private static final Logger log = LoggerFactory.getLogger(GameSessionProvisioningController.class);

    private final EngineNetworkLifecycle engineNetworkLifecycle;
    private final GameSessionDefinitionMapper mapper;

    public GameSessionProvisioningController(EngineNetworkLifecycle engineNetworkLifecycle, GameSessionDefinitionMapper mapper) {
        this.engineNetworkLifecycle = engineNetworkLifecycle;
        this.mapper = mapper;
    }

    @PostMapping("/internal/game-sessions/{roomId}/definition")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> provision(
            @PathVariable("roomId") String roomId, @RequestBody GameSessionDefinitionRequest request) {
        // Tomcat can start accepting requests before EngineNetworkLifecycle (an ApplicationRunner)
        // finishes wiring the room-store connection -- treat that window as "come back shortly",
        // never NPE.
        var definitionStore = engineNetworkLifecycle.gameSessionDefinitionStore();
        if (definitionStore == null) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "engine still starting up, retry shortly")));
        }

        byte[] jsonBytes;
        try {
            // Validate now (DefinitionLoader guardrails) so a bad request fails with 400 here,
            // never silently stored to only fail later when a student actually tries to join.
            mapper.validate(request);
            jsonBytes = mapper.toJsonBytes(request);
        } catch (DefinitionRejectedException e) {
            log.warn("room {}: rejected provisioning request: {}", roomId, e.getMessage());
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage())));
        }

        return definitionStore.save(roomId, jsonBytes)
                .thenApply(ignored -> {
                    log.info("room {}: game session definition provisioned ({} question(s))", roomId,
                            request.questions() == null ? 0 : request.questions().size());
                    return ResponseEntity.ok(Map.<String, Object>of("roomId", roomId, "status", "provisioned"));
                })
                .exceptionally(error -> {
                    log.error("room {}: failed to persist provisioned definition", roomId, error);
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(Map.of("error", "could not reach room-store, retry later"));
                });
    }
}
