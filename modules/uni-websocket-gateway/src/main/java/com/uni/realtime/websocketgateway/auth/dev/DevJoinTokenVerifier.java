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
