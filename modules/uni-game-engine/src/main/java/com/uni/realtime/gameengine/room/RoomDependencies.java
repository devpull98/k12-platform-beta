package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.events.GameEventPublisher;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import org.apache.pekko.actor.typed.ActorRef;

import java.time.Clock;
import java.util.Objects;

public record RoomDependencies(
        Clock clock,
        ScoreCalculator scoreCalculator,
        EngineMetrics engineMetrics,
        ActorRef<GameMessage> broadcastTarget,
        RoomSnapshotStore snapshotStore,
        GameEventPublisher gameEventPublisher
) {
    public RoomDependencies {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(scoreCalculator, "scoreCalculator must not be null");
    }

    public static RoomDependencies of(Clock clock, ScoreCalculator scoreCalculator, EngineMetrics engineMetrics,
                                      ActorRef<GameMessage> broadcastTarget, RoomSnapshotStore snapshotStore,
                                      GameEventPublisher gameEventPublisher) {
        return new RoomDependencies(clock, scoreCalculator, engineMetrics, broadcastTarget, snapshotStore, gameEventPublisher);
    }
}
