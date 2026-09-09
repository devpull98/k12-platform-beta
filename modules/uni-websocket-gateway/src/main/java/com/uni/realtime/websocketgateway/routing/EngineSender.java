package com.uni.realtime.websocketgateway.routing;

import com.uni.realtime.protocol.GameMessage;

public interface EngineSender {

    void send(GameMessage message);
}
