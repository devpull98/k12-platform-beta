package com.uni.realtime.engine.boot;

import com.uni.realtime.engine.metrics.EngineMetrics;
import com.uni.realtime.engine.room.ModuloRoomOwnership;
import com.uni.realtime.engine.room.RoomOwnership;
import com.uni.realtime.engine.room.RoomSupervisor;
import com.uni.realtime.engine.scoring.FormulaScoreCalculator;
import com.uni.realtime.engine.net.FrameChannelServer;
import org.apache.pekko.actor.typed.ActorSystem;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Task 13: actually starts the internal frame channel and the actor system on process boot --
 * before this, {@code EngineApplication} booted Spring and nothing else (its own javadoc said
 * so explicitly: "neither is started yet"). Unlike the Gateway side (see {@code
 * GatewayNetworkLifecycle}), nothing here waits on an external decision -- {@link RoomOwnership}
 * needs only this pod's own configuration, not a signed-ticket format from another team.
 */
@Component
public final class EngineNetworkLifecycle implements ApplicationRunner, DisposableBean {

    private final EngineMetrics engineMetrics;
    private final String podId;
    private final int podCount;
    private final int framePort;

    private ActorSystem<RoomSupervisor.Command> system;
    private FrameChannelServer frameChannelServer;

    public EngineNetworkLifecycle(EngineMetrics engineMetrics,
            @Value("${uni.engine.pod-id}") String podId,
            @Value("${uni.engine.pod-count}") int podCount,
            @Value("${uni.engine.frame-port}") int framePort) {
        this.engineMetrics = engineMetrics;
        this.podId = podId;
        this.podCount = podCount;
        this.framePort = framePort;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // §7.5/decision B2: room_id % pod-count, isolated behind RoomOwnership. Pod naming
        // ("engine-0".."engine-{pod-count-1}") matches application.yml's ENGINE_POD_ID default.
        List<String> podIds = IntStream.range(0, podCount).mapToObj(i -> "engine-" + i).toList();
        RoomOwnership roomOwnership = new ModuloRoomOwnership(podId, podIds);

        system = ActorSystem.create(
                RoomSupervisor.create(roomOwnership, FormulaScoreCalculator.binaryChoice(), engineMetrics, Clock.systemUTC()),
                "engine");

        frameChannelServer = new FrameChannelServer(framePort, roomOwnership,
                (channel, message) -> system.tell(new RoomSupervisor.Dispatch(message, channel)),
                channel -> system.tell(new RoomSupervisor.ChannelClosed(channel)),
                engineMetrics);
        frameChannelServer.start();
    }

    @Override
    public void destroy() throws Exception {
        if (frameChannelServer != null) {
            frameChannelServer.shutdown();
        }
        if (system != null) {
            system.terminate();
        }
    }
}
