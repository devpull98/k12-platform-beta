package com.uni.realtime.engine.events;

/**
 * Task 18: the narrow slice {@link GameEventPublisher}'s worker thread needs to actually ship
 * an event, kept as an interface so the queue/isolation logic is testable without a real Kafka
 * broker (the production implementation, {@code KafkaGameEventSink}, is not exercised by any
 * test in this repo -- no Kafka available in this environment, same caveat already carried by
 * {@code DistributedRoomLeaseStore}/{@code DistributedRoomSnapshotStore}).
 *
 * <p>{@link #send} is allowed to block or throw -- unlike
 * {@code com.uni.realtime.engine.room.RoomLeaseStore}/{@code RoomSnapshotStore} it does not need
 * to be async, because {@link GameEventPublisher} only ever calls it from its own dedicated
 * worker thread, never from {@code RoomActor}'s.
 */
public interface GameEventSink extends AutoCloseable {

    void send(String partitionKey, byte[] payload) throws Exception;

    @Override
    void close() throws Exception;
}
