package com.uni.realtime.websocketgateway.auth.dev;

import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlwaysAcceptJoinTokenVerifierTest {

    private final AlwaysAcceptJoinTokenVerifier verifier = new AlwaysAcceptJoinTokenVerifier();

    @Test
    void should_acceptAnyWellShapedToken_withoutCheckingAnySignature() throws JoinTokenRejectedException {
        JoinTokenClaims claims = verifier.verify("join-token:student-1:room-1");

        assertThat(claims.studentId()).isEqualTo("student-1");
        assertThat(claims.roomId()).isEqualTo("room-1");
        assertThat(claims.sessionId()).isEqualTo("session-student-1");
        assertThat(claims.roles()).containsExactly("student");
    }

    @Test
    void should_rejectAToken_missingTheJoinTokenPrefix() {
        assertThatThrownBy(() -> verifier.verify("student-1:room-1"))
                .isInstanceOf(JoinTokenRejectedException.class);
    }

    @Test
    void should_rejectAToken_withTheWrongFieldCount() {
        assertThatThrownBy(() -> verifier.verify("join-token:student-1"))
                .isInstanceOf(JoinTokenRejectedException.class);
    }

    @Test
    void should_rejectAnEmptyToken() {
        assertThatThrownBy(() -> verifier.verify(""))
                .isInstanceOf(JoinTokenRejectedException.class);
    }
}
