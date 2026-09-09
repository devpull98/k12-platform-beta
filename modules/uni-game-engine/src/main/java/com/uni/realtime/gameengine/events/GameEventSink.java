package com.uni.realtime.gameengine.events;

public interface GameEventSink extends AutoCloseable {

    void send(String partitionKey, byte[] payload) throws Exception;

    @Override
    void close() throws Exception;
}
