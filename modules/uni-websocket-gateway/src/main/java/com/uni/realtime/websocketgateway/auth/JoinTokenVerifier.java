package com.uni.realtime.websocketgateway.auth;

public interface JoinTokenVerifier {

    /**
     * @throws JoinTokenRejectedException the joinToken is expired, malformed, or fails signature
     *                                  verification -- the caller must close the channel
     */
    JoinTokenClaims verify(String joinToken) throws JoinTokenRejectedException;
}
