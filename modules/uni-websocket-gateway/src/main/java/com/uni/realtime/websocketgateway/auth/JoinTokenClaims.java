package com.uni.realtime.websocketgateway.auth;

import java.util.List;

/**
 * The minimal claim set tech-design.md §G1 R3 settles on. This is what gets bound into
 * {@link com.uni.realtime.websocketgateway.net.ChannelAttributes} -- never the client-supplied
 * {@code GameMessage} fields, which are untrusted until a join token has verified them.
 */
public record JoinTokenClaims(String studentId, String roomId, String sessionId, List<String> roles) {}
