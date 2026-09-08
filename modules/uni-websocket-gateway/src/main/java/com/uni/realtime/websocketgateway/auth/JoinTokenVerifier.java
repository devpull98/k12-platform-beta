package com.uni.realtime.websocketgateway.auth;

/**
 * Isolated on purpose (tech-design.md §G1a): the signing algorithm, key distribution and
 * encoding of the one-time joinToken are a fact the platform team owns (it already issues
 * joinTokens via {@code POST /session/{id}/join}) -- this repo is not allowed to invent a
 * format. Only the minimal claim set is settled (§G1 R3: student_id, room_id, session_id,
 * roles, exp), so that is all this contract exposes.
 *
 * <p>There is deliberately no implementation of this interface in main code yet. Wiring a
 * real one in before G1a/G1c are answered would either bake in a guessed format or ship an
 * insecure stand-in that looks production-ready.
 */
public interface JoinTokenVerifier {

    /**
     * @throws JoinTokenRejectedException the joinToken is expired, malformed, or fails signature
     *                                  verification -- the caller must close the channel
     */
    JoinTokenClaims verify(String joinToken) throws JoinTokenRejectedException;
}
