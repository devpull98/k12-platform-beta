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
