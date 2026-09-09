package com.uni.realtime.websocketgateway.auth;

import java.util.List;

public record JoinTokenClaims(String studentId, String roomId, String sessionId, List<String> roles) {}
