package com.uni.realtime.websocketgateway.auth.dev;

import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;
import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Local-Docker-only stand-in for the real, externally-owned {@link JoinTokenVerifier} (G1a/G1c --
 * see that interface's javadoc), even more permissive than {@link DevJoinTokenVerifier}: it
 * performs NO signature check at all -- any string shaped
 * {@code "join-token:<student_id>:<room_id>"} is accepted outright. Same shape the in-test
 * {@code FakeJoinTokenVerifier}s in {@code WalkingSkeletonTest}/{@code RoomActorResyncTest} already
 * use, promoted here to an actual, opt-in Spring bean so a manual/demo client can connect without
 * running {@link DevJoinTokenCodec}'s HMAC minting flow first.
 *
 * <p><b>NEVER valid for staging/production -- more so than {@link DevJoinTokenVerifier}</b>, which
 * at least checks an HMAC signature. This class verifies nothing about who is asking to join what.
 * Gated behind BOTH the {@code dev-docker} Spring profile AND
 * {@code uni.gateway.always-accept-join-token.enabled=true} so a stray profile activation or a
 * stray property flip alone can never accidentally light this up.
 *
 * <p>Mutually exclusive with {@link DevJoinTokenVerifier} in practice: both are
 * {@code JoinTokenVerifier} beans, so enabling both flags at once fails Gateway startup loudly
 * (ambiguous constructor argument for {@code GatewayNetworkLifecycle}) instead of silently
 * resolving to one of them -- deliberately not annotated {@code @Primary}, so a misconfiguration
 * is a startup crash an operator has to notice, not a quiet security downgrade.
 */
@Component
@Profile("dev-docker")
@ConditionalOnProperty(prefix = "uni.gateway.always-accept-join-token", name = "enabled", havingValue = "true")
public final class AlwaysAcceptJoinTokenVerifier implements JoinTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(AlwaysAcceptJoinTokenVerifier.class);

    public AlwaysAcceptJoinTokenVerifier() {
        log.warn("AlwaysAcceptJoinTokenVerifier ACTIVE -- accepts ANY join token unchecked, "
                + "local/Docker development only, NEVER valid for staging/production");
    }

    @Override
    public JoinTokenClaims verify(String joinToken) throws JoinTokenRejectedException {
        String[] parts = joinToken.split(":");
        if (parts.length != 3 || !parts[0].equals("join-token")) {
            throw new JoinTokenRejectedException(
                    "AlwaysAcceptJoinTokenVerifier: malformed join token, expected "
                            + "\"join-token:<student_id>:<room_id>\", got: " + joinToken);
        }
        String studentId = parts[1];
        String roomId = parts[2];
        return new JoinTokenClaims(studentId, roomId, "session-" + studentId, List.of("student"));
    }
}
