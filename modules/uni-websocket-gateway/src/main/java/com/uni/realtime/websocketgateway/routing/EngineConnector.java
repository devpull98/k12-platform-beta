package com.uni.realtime.websocketgateway.routing;

/**
 * The narrow slice of {@link FrameChannelClient} that {@link EnginePodDiscovery} needs --
 * separated out so discovery's polling/diffing logic can be unit tested with a fake instead of
 * real sockets (this connector is exactly the thing under test's collaborator, not vice versa).
 */
public interface EngineConnector {

    /** Whether a connection to this pod id is already open. */
    boolean isConnected(String podId);

    /** Opens a new connection. May throw if the pod is unreachable this attempt. */
    void connect(String podId, String host, int port) throws Exception;
}
