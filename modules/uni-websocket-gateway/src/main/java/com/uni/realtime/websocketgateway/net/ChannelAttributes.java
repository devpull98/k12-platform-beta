package com.uni.realtime.websocketgateway.net;

import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.List;

public final class ChannelAttributes {

    public static final AttributeKey<String> STUDENT_ID = AttributeKey.valueOf("student_id");
    public static final AttributeKey<String> ROOM_ID = AttributeKey.valueOf("room_id");
    public static final AttributeKey<String> SESSION_ID = AttributeKey.valueOf("session_id");
    public static final AttributeKey<List<String>> ROLES = AttributeKey.valueOf("roles");

    public static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("trace_id");

    private ChannelAttributes() {}

    public static void bind(Channel channel, JoinTokenClaims claims, String traceId) {
        channel.attr(STUDENT_ID).set(claims.studentId());
        channel.attr(ROOM_ID).set(claims.roomId());
        channel.attr(SESSION_ID).set(claims.sessionId());
        channel.attr(ROLES).set(claims.roles());
        channel.attr(TRACE_ID).set(traceId);
    }
}
