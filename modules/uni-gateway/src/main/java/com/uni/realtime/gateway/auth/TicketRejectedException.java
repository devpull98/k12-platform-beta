package com.uni.realtime.gateway.auth;

/** Expired, malformed, replayed, or unverifiable ticket -- always fatal to the connection. */
public final class TicketRejectedException extends Exception {

    public TicketRejectedException(String message) {
        super(message);
    }
}
