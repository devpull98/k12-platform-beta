package com.uni.realtime.gateway.auth.dev;

import com.uni.realtime.gateway.auth.TicketClaims;
import com.uni.realtime.gateway.auth.TicketRejectedException;
import com.uni.realtime.gateway.auth.TicketVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Local-Docker-only stand-in for the real, externally-owned {@link TicketVerifier} (G1a/G1c --
 * see that interface's javadoc). This is registering the FIRST {@code TicketVerifier} bean this
 * codebase has ever had, which is what actually lights up {@code GatewayNetworkLifecycle}
 * (it is {@code @ConditionalOnBean(TicketVerifier.class)}) and opens the game WebSocket port for
 * local testing.
 *
 * <p><b>NEVER valid for staging/production.</b> Gated behind BOTH the {@code dev-docker} Spring
 * profile AND {@code uni.gateway.dev-ticket.enabled=true} so that a stray profile activation or a
 * stray property flip alone can never accidentally light this up outside a deliberately-configured
 * local Docker stack. Tickets are signed/verified by {@link DevTicketCodec}, a format this repo
 * invented purely for local testing -- it has no relationship to whatever signing algorithm G1a/
 * G1c eventually settle on.
 */
@Component
@Profile("dev-docker")
@ConditionalOnProperty(prefix = "uni.gateway.dev-ticket", name = "enabled", havingValue = "true")
public final class DevTicketVerifier implements TicketVerifier {

    private static final Logger log = LoggerFactory.getLogger(DevTicketVerifier.class);

    private final DevTicketCodec codec;
    private final DevTicketReplayGuard replayGuard;

    public DevTicketVerifier(
            @Value("${uni.gateway.dev-ticket.hmac-secret}") String hmacSecret,
            @Value("${uni.gateway.dev-ticket.replay-guard-enabled:false}") boolean replayGuardEnabled) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException(
                    "uni.gateway.dev-ticket.enabled=true but uni.gateway.dev-ticket.hmac-secret is unset");
        }
        // No injected Clock bean exists anywhere in this codebase (IpAdmissionController/
        // RateLimitHandler both construct Clock.systemUTC() directly) -- matching that
        // convention rather than introducing the first Spring-managed Clock bean.
        Clock clock = Clock.systemUTC();
        this.codec = new DevTicketCodec(hmacSecret.getBytes(StandardCharsets.UTF_8), clock);
        this.replayGuard = replayGuardEnabled ? new DevTicketReplayGuard(clock) : null;
        log.warn("DevTicketVerifier ACTIVE -- local/Docker development only, NEVER valid for staging/production");
    }

    @Override
    public TicketClaims verify(String ticket) throws TicketRejectedException {
        DevTicketCodec.DecodedDevTicket decoded = codec.verifyRaw(ticket);
        if (replayGuard != null) {
            replayGuard.checkAndMark(decoded.jti(), decoded.expEpochMs());
        }
        return decoded.claims();
    }
}
