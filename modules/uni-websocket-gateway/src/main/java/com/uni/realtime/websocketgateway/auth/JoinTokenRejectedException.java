package com.uni.realtime.websocketgateway.auth;

/** Expired, malformed, replayed, or unverifiable join token -- always fatal to the connection. */
public final class JoinTokenRejectedException extends Exception {

    public JoinTokenRejectedException(String message) {
        super(message);
    }
}
