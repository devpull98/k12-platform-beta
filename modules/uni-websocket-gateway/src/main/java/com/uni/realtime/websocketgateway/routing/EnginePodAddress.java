package com.uni.realtime.websocketgateway.routing;

/** One Engine pod's dial address, as returned by an {@link EnginePodResolver}. */
public record EnginePodAddress(String podId, String host, int port) {
}
