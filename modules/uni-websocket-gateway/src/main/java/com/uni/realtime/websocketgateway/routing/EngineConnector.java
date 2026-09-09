package com.uni.realtime.websocketgateway.routing;

public interface EngineConnector {

    /** Whether a connection to this pod id is already open. */
    boolean isConnected(String podId);

    /** Opens a new connection. May throw if the pod is unreachable this attempt. */
    void connect(String podId, String host, int port) throws Exception;
}
