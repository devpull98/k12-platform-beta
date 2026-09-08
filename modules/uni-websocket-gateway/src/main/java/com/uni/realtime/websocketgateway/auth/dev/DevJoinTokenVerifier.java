package com.uni.realtime.websocketgateway.auth.dev;

import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;
import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Local-Docker-only stand-in for the real, externally-owned {@link JoinTokenVerifier} (G1a/G1c --
 * see that interface's javadoc). This is registering the FIRST {@code JoinTokenVerifier} bean this
 * codebase has ever had, which is what actually lights up {@code GatewayNetworkLifecycle}
 * (it is {@code @ConditionalOnBean(JoinTokenVerifier.class)}) and opens the game WebSocket port for
 * local testing.
 *
 * <p><b>NEVER valid for staging/production.</b> Gated behind BOTH the {@code dev-docker} Spring
 * profile AND {@code uni.gateway.dev-join-token.enabled=true} so that a stray profile activation or a
 * stray property flip alone can never accidentally light this up outside a deliberately-configured
 * local Docker stack. JoinTokens are signed/verified by {@link DevJoinTokenCodec}, a format this repo
 * invented purely for local testing -- it has no relationship to whatever signing algorithm G1a/
 * G1c eventually settle on.
 */
@Component
@Profile("dev-docker")
@ConditionalOnProperty(prefix = "uni.gateway.dev-join-token", name = "enabled", havingValue = "true")
public final class DevJoinTokenVerifier implements JoinTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(DevJoinTokenVerifier.class);

    private final DevJoinTokenCodec codec;
    private final DevJoinTokenReplayGuard replayGuard;

    public DevJoinTokenVerifier(
            @Value("${uni.gateway.dev-join-token.hmac-secret}") String hmacSecret,
            @Value("${uni.gateway.dev-join-token.replay-guard-enabled:false}") boolean replayGuardEnabled) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException(
                    "uni.gateway.dev-join-token.enabled=true but uni.gateway.dev-join-token.hmac-secret is unset");
        }
        // No injected Clock bean exists anywhere in this codebase (IpAdmissionController/
        // RateLimitHandler both construct Clock.systemUTC() directly) -- matching that
        // convention rather than introducing the first Spring-managed Clock bean.
        Clock clock = Clock.systemUTC();
        this.codec = new DevJoinTokenCodec(hmacSecret.getBytes(StandardCharsets.UTF_8), clock);
        this.replayGuard = replayGuardEnabled ? new DevJoinTokenReplayGuard(clock) : null;
        log.warn("DevJoinTokenVerifier ACTIVE -- local/Docker development only, NEVER valid for staging/production");
    }

    @Override
    public JoinTokenClaims verify(String joinToken) throws JoinTokenRejectedException {
        DevJoinTokenCodec.DecodedDevJoinToken decoded = codec.verifyRaw(joinToken);
        if (replayGuard != null) {
            replayGuard.checkAndMark(decoded.jti(), decoded.expEpochMs());
        }
        return decoded.claims();
    }
}
