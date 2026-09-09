package com.uni.realtime.e2e;

import com.uni.realtime.e2e.support.SimulatedStudentClient;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.metrics.EngineMetrics;
import com.uni.realtime.gameengine.net.FrameChannelServer;
import com.uni.realtime.gameengine.room.ModuloRoomOwnership;
import com.uni.realtime.gameengine.room.RoomActor;
import com.uni.realtime.gameengine.room.RoomOwnership;
import com.uni.realtime.gameengine.room.RoomSupervisor;
import com.uni.realtime.gameengine.scoring.FormulaScoreCalculator;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.JoinRoom;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.TeamAssignment;
import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;
import com.uni.realtime.websocketgateway.auth.JoinTokenVerifier;
import com.uni.realtime.websocketgateway.fanout.Broadcaster;
import com.uni.realtime.websocketgateway.fanout.RoomRegistry;
import com.uni.realtime.websocketgateway.metrics.GatewayMetrics;
import com.uni.realtime.websocketgateway.net.EngineResponseRouter;
import com.uni.realtime.websocketgateway.net.GatewayBootstrap;
import com.uni.realtime.websocketgateway.net.IpAdmissionController;
import com.uni.realtime.websocketgateway.net.StudentHandshakeAdmissionController;
import com.uni.realtime.websocketgateway.routing.FrameChannelClient;
import com.uni.realtime.websocketgateway.routing.RouteCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Task 26: real-socket E2E coverage for the 2 BDD scenarios this repo can actually run today
 * (INCLASS-GAME-001-cooperative-boss.feature, INCLASS-GAME-002-team-speed-race.feature) --
 * INCLASS-GAME-003-lms-worker-sync.feature is NOT covered here, on purpose: plan.md Task 24
 * stopped at documenting findings (real lms-worker topics/format/required LMS ids are a separate,
 * still-open integration question), so there is no producer to exercise.
 *
 * <p>No Cucumber anywhere in this repo (checked before starting this task) -- per user decision,
 * written as plain JUnit5 real-socket tests, same convention {@link WalkingSkeletonTest} already
 * established: real {@link SimulatedStudentClient} sockets end to end through a real {@code
 * GatewayBootstrap}/{@code FrameChannelServer}/{@code RoomSupervisor}, {@link FakeJoinTokenVerifier}
 * standing in for G1a/G1c exactly as {@code JoinTokenAuthHandler}'s own javadoc requires, question
 * content driven through {@code RoomSupervisor.GetRoomActor} (no game-definition-authoring wire
 * format decided yet, Task 11).
 *
 * <p>The one addition beyond {@code WalkingSkeletonTest}'s pattern: {@code
 * RoomSupervisor.SpawnConfiguredRoom} (added this task, same test/ops-only spirit as {@code
 * GetRoomActor}) pre-spawns a room with {@code GAME_MODE_COOPERATIVE}/{@code TEAM} config BEFORE
 * any client joins -- {@code RoomSupervisor.spawnRoom()}'s real join path still only ever spawns
 * {@code SOLO} rooms (no {@code GameDefinition} source exists yet, plan.md P1 Task 11's still-open
 * gap), so this is the only way to get such a room to exist for a real WebSocket client to join.
 */
class InclassGroupGameE2ETest {

    private static final int TEAM_SIZE = 3;

    private EventLoopGroup gatewayClientGroup;
    private ActorSystem<RoomSupervisor.Command> engineSystem;
    private FrameChannelServer engineServer;
    private GatewayBootstrap gatewayBootstrap;
    private final List<SimulatedStudentClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (SimulatedStudentClient client : clients) {
            client.close();
        }
        if (gatewayBootstrap != null) gatewayBootstrap.shutdown();
        if (gatewayClientGroup != null) gatewayClientGroup.shutdownGracefully().sync();
        if (engineServer != null) engineServer.shutdown();
        if (engineSystem != null) engineSystem.terminate();
    }

    @Test
    void should_reachProgressCompletedAndBroadcastGameOver_forCooperativeBossScenario() throws Exception {
        int gatewayPort = startEngineAndGateway();
        String roomId = "room-boss-101";
        List<ProgressStage> stages = List.of(
                new ProgressStage(0, "boss-0.svg"), new ProgressStage(30, "boss-30.svg"),
                new ProgressStage(70, "boss-70.svg"), new ProgressStage(100, "boss-100.svg"));
        spawnConfiguredRoom(roomId, GameMode.GAME_MODE_COOPERATIVE, 10, stages,
                SharedResourceType.NONE, 0, List.of(), ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED);

        List<SimulatedStudentClient> students = joinStudents(gatewayPort, roomId, 12);
        startQuestion(roomId, "q-1", List.of("a"));

        // 3 correct answers -> 30%, stage_index 1 (INCLASS-GAME-001-cooperative-boss.feature).
        for (int i = 0; i < 3; i++) {
            students.get(i).submitTrackedAnswer(roomId, "q-1", List.of("a"));
        }
        GameMessage progress30 = students.get(0).takeMatching("30% progress delta",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getProgress().getProgressPercentage() == 30);
        assertThat(progress30.getRoomStateSnapshot().getProgress().getStageIndex()).isEqualTo(1);

        // 4 more -> 70%, stage_index 2.
        startQuestion(roomId, "q-2", List.of("a"));
        for (int i = 3; i < 7; i++) {
            students.get(i).submitTrackedAnswer(roomId, "q-2", List.of("a"));
        }
        GameMessage progress70 = students.get(0).takeMatching("70% progress delta",
                m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getProgress().getProgressPercentage() == 70);
        assertThat(progress70.getRoomStateSnapshot().getProgress().getStageIndex()).isEqualTo(2);

        // Final 3 -> 100%, progress_completed -> GameOver to the WHOLE room, not just the submitters.
        startQuestion(roomId, "q-3", List.of("a"));
        for (int i = 7; i < 10; i++) {
            students.get(i).submitTrackedAnswer(roomId, "q-3", List.of("a"));
        }
        GameMessage gameOverOnSubmitter = students.get(9).takeMatching("GameOver on the finishing submitter",
                m -> m.getType() == MessageType.GAME_OVER);
        GameMessage gameOverOnBystander = students.get(11).takeMatching("GameOver on a student who never answered",
                m -> m.getType() == MessageType.GAME_OVER);
        assertThat(gameOverOnSubmitter.getGameOver().getReason()).isEqualTo("progress_completed");
        assertThat(gameOverOnBystander.getGameOver().getReason()).isEqualTo("progress_completed");
        assertThat(gameOverOnSubmitter.getGameOver().getWinnerId()).isEmpty(); // whole room wins together
    }

    @Test
    void should_scopeDraftSyncToTeammatesAndDeclareFirstToFinishWinner_forTeamSpeedRaceScenario() throws Exception {
        int gatewayPort = startEngineAndGateway();
        String roomId = "room-team-202";
        List<TeamAssignment> teams = List.of(
                team("A", "student-01", "student-02", "student-03"),
                team("B", "student-04", "student-05", "student-06"),
                team("C", "student-07", "student-08", "student-09"),
                team("D", "student-10", "student-11", "student-12"));
        spawnConfiguredRoom(roomId, GameMode.GAME_MODE_TEAM, 2, List.of(),
                SharedResourceType.NONE, 0, teams, ScoreAggregation.SUM_ALL, WinCondition.FIRST_TO_FINISH);

        List<SimulatedStudentClient> students = joinStudentsWithIds(gatewayPort, roomId,
                teams.stream().flatMap(t -> t.getStudentIdsList().stream()).toList());
        SimulatedStudentClient studentA1 = students.get(0); // student-01, Team A
        SimulatedStudentClient studentA2 = students.get(1); // student-02, Team A
        SimulatedStudentClient studentA3 = students.get(2); // student-03, Team A
        SimulatedStudentClient studentB1 = students.get(3); // student-04, Team B

        // Let the real Pekko coalescing timer (RoomActor uses a genuine Clock.systemUTC() here,
        // unlike the BehaviorTestKit unit tests) settle every roster-join delta it owes each
        // client before asserting silence below -- otherwise a legitimate, unrelated join delta
        // arriving mid-window would look like a false failure of the draft-scoping assertion.
        Thread.sleep(300);
        clients.forEach(SimulatedStudentClient::clearReceived);

        // Scenario "Scoped Draft Sync": only Team A's OTHER members receive it, never Team B/C/D.
        studentA1.send(updateDraft(roomId, "student-01", "Phuong trinh bac 2"));
        GameMessage draftOnA2 = studentA2.takeMatching("Team A member sees the draft",
                m -> m.getType() == MessageType.UPDATE_DRAFT);
        GameMessage draftOnA3 = studentA3.takeMatching("Team A member sees the draft",
                m -> m.getType() == MessageType.UPDATE_DRAFT);
        assertThat(draftOnA2.getDraftUpdate().getDraftContent()).isEqualTo("Phuong trinh bac 2");
        assertThat(draftOnA3.getDraftUpdate().getDraftContent()).isEqualTo("Phuong trinh bac 2");
        studentB1.assertNoMoreMessagesFor(500); // Team B must never see Team A's draft

        // Scenario "First to finish": Team A answers progress_target=2 questions correctly first.
        // Each submit is confirmed accepted before moving on, so a slow ANSWER_ACK round trip
        // never races ahead of the GameOver wait below.
        startQuestion(roomId, "q-1", List.of("a"));
        studentA1.submitTrackedAnswer(roomId, "q-1", List.of("a"));
        assertThat(studentA1.takeMatching("ack for q-1", m -> m.getType() == MessageType.ANSWER_ACK)
                .getAnswerAck().getAccepted()).isTrue();
        startQuestion(roomId, "q-2", List.of("a"));
        studentA2.submitTrackedAnswer(roomId, "q-2", List.of("a"));
        assertThat(studentA2.takeMatching("ack for q-2", m -> m.getType() == MessageType.ANSWER_ACK)
                .getAnswerAck().getAccepted()).isTrue();

        GameMessage gameOverOnA = studentA3.takeMatching("GameOver on a Team A member who never answered",
                m -> m.getType() == MessageType.GAME_OVER);
        GameMessage gameOverOnOtherTeam = students.get(11).takeMatching("GameOver reaches every team, not just A",
                m -> m.getType() == MessageType.GAME_OVER);
        assertThat(gameOverOnA.getGameOver().getReason()).isEqualTo("first_to_finish");
        assertThat(gameOverOnA.getGameOver().getWinnerId()).isEqualTo("A");
        assertThat(gameOverOnOtherTeam.getGameOver().getWinnerId()).isEqualTo("A");
    }

    private int startEngineAndGateway() throws InterruptedException {
        RoomOwnership ownsEverything = new ModuloRoomOwnership("engine-0", List.of("engine-0"));
        engineSystem = ActorSystem.create(RoomSupervisor.create(ownsEverything,
                FormulaScoreCalculator.binaryChoice(), new EngineMetrics(new SimpleMeterRegistry()), Clock.systemUTC()), "e2e-engine");
        engineServer = new FrameChannelServer(0, ownsEverything,
                (channel, message) -> engineSystem.tell(new RoomSupervisor.Dispatch(message, channel)),
                channel -> engineSystem.tell(new RoomSupervisor.ChannelClosed(channel)),
                new EngineMetrics(new SimpleMeterRegistry()));
        engineServer.start();
        return startGateway(engineServer.boundPort());
    }

    private int startGateway(int enginePort) throws InterruptedException {
        RoomRegistry roomRegistry = new RoomRegistry();
        GatewayMetrics gatewayMetrics = new GatewayMetrics(new SimpleMeterRegistry());
        Broadcaster broadcaster = new Broadcaster(roomRegistry, gatewayMetrics);
        EngineResponseRouter responseRouter = new EngineResponseRouter(roomRegistry, broadcaster);
        RouteCache routeCache = new RouteCache();
        gatewayClientGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        FrameChannelClient frameChannelClient = new FrameChannelClient(routeCache, responseRouter::route,
                responseRouter::broadcastConnectionDegraded, gatewayClientGroup, gatewayMetrics);
        frameChannelClient.connect("engine-0", "localhost", enginePort);

        gatewayBootstrap = new GatewayBootstrap(0, new FakeJoinTokenVerifier(), roomRegistry, gatewayMetrics,
                new IpAdmissionController(), new StudentHandshakeAdmissionController(), frameChannelClient);
        gatewayBootstrap.start();
        return gatewayBootstrap.boundPort();
    }

    private void spawnConfiguredRoom(String roomId, GameMode gameMode, int progressTarget,
            List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition) {
        TestProbe<ActorRef<RoomActor.Command>> probe = TestProbe.create(engineSystem);
        engineSystem.tell(new RoomSupervisor.SpawnConfiguredRoom(roomId, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation, winCondition, probe.getRef()));
        probe.receiveMessage(); // block until the room genuinely exists before any client joins it
    }

    private void startQuestion(String roomId, String questionId, List<String> correctAnswerIds) {
        TestProbe<ActorRef<RoomActor.Command>> probe = TestProbe.create(engineSystem);
        engineSystem.tell(new RoomSupervisor.GetRoomActor(roomId, probe.getRef()));
        ActorRef<RoomActor.Command> room = probe.receiveMessage();
        room.tell(new RoomActor.StartQuestion(questionId, 25_000, correctAnswerIds));
        // First question also (re)starts the game; StartGame is idempotent-safe to call every time
        // since RoomState.startGame() just re-sets phase = PLAYING.
        room.tell(new RoomActor.StartGame());
    }

    private List<SimulatedStudentClient> joinStudents(int gatewayPort, String roomId, int count) throws InterruptedException {
        return joinStudentsWithIds(gatewayPort, roomId,
                java.util.stream.IntStream.rangeClosed(1, count)
                        .mapToObj(i -> String.format("student-%02d", i)).toList());
    }

    private List<SimulatedStudentClient> joinStudentsWithIds(int gatewayPort, String roomId, List<String> studentIds)
            throws InterruptedException {
        List<SimulatedStudentClient> joined = new ArrayList<>();
        for (String studentId : studentIds) {
            SimulatedStudentClient client = SimulatedStudentClient.connect(gatewayPort);
            clients.add(client);
            client.send(GameMessage.newBuilder()
                    .setType(MessageType.JOIN_ROOM)
                    .setJoinRoom(JoinRoom.newBuilder().setJoinToken("join-token:" + studentId + ":" + roomId)
                            .setDisplayName(studentId))
                    .build());
            client.takeMatching(studentId + "'s full snapshot",
                    m -> m.getType() == MessageType.ROOM_STATE_SNAPSHOT && m.getRoomStateSnapshot().getFull());
            joined.add(client);
        }
        return joined;
    }

    private static GameMessage updateDraft(String roomId, String studentId, String draftContent) {
        return GameMessage.newBuilder()
                .setType(MessageType.UPDATE_DRAFT)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .setDraftUpdate(com.uni.realtime.protocol.DraftUpdate.newBuilder().setDraftContent(draftContent))
                .build();
    }

    private static TeamAssignment team(String teamId, String... studentIds) {
        return TeamAssignment.newBuilder()
                .setTeamId(teamId)
                .setTeamName("Team " + teamId)
                .addAllStudentIds(List.of(studentIds))
                .build();
    }

    /** Same test-only stand-in {@link WalkingSkeletonTest} uses -- G1a/G1c is still unresolved. */
    private static final class FakeJoinTokenVerifier implements JoinTokenVerifier {
        @Override
        public JoinTokenClaims verify(String joinToken) throws JoinTokenRejectedException {
            String[] parts = joinToken.split(":");
            if (parts.length != 3 || !parts[0].equals("join-token")) {
                throw new JoinTokenRejectedException("malformed test joinToken: " + joinToken);
            }
            return new JoinTokenClaims(parts[1], parts[2], "session-" + parts[1], List.of("student"));
        }
    }
}
