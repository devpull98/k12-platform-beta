package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.auth.TicketClaims;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.List;

/**
 * Identity bound to a Channel once, at handshake, by {@code TicketAuthHandler} (§10.6). Every
 * handler downstream reads identity from here -- never from a client-supplied
 * {@code GameMessage} field, which is exactly the distinction that makes a disagreeing
 * payload {@code room_id} a security event instead of a routing hint.
 */
public final class ChannelAttributes {

    public static final AttributeKey<String> STUDENT_ID = AttributeKey.valueOf("student_id");
    public static final AttributeKey<String> ROOM_ID = AttributeKey.valueOf("room_id");
    public static final AttributeKey<String> SESSION_ID = AttributeKey.valueOf("session_id");
    public static final AttributeKey<List<String>> ROLES = AttributeKey.valueOf("roles");

    /**
     * §15.3: generated fresh per connection at handshake (not carried by the ticket), so an
     * Engine-side log line can be joined back to the Gateway span that produced it.
     */
    public static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("trace_id");

    private ChannelAttributes() {}

    public static void bind(Channel channel, TicketClaims claims, String traceId) {
        channel.attr(STUDENT_ID).set(claims.studentId());
        channel.attr(ROOM_ID).set(claims.roomId());
        channel.attr(SESSION_ID).set(claims.sessionId());
        channel.attr(ROLES).set(claims.roles());
        channel.attr(TRACE_ID).set(traceId);
    }
}
