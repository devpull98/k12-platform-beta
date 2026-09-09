package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
import com.uni.realtime.gameengine.scoring.WinConditionEvaluator;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.CommittedSeq;
import com.uni.realtime.protocol.DraftUpdate;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.GameOver;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.PlayerState;
import com.uni.realtime.protocol.ProgressMeterSnapshot;
import com.uni.realtime.protocol.RejectReason;
import com.uni.realtime.protocol.RoomStateSnapshot;
import com.uni.realtime.protocol.SharedResourceState;
import com.uni.realtime.protocol.TeamAssignment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RoomState {

    private static final long GRACE_MS = 500;
    private static final int FULL_SNAPSHOT_EVERY_N_FLUSHES = 10;

    private final String roomId;
    private final Clock clock;
    private final ScoreCalculator scoreCalculator;
    private final MissedStepPolicy missedStepPolicy;

    private final GameMode gameMode;
    private final int progressTarget;
    private final List<ProgressStage> progressStages;
    private final SharedResourceType sharedResourceType;
    private final int sharedResourcePenalty;
    private final List<TeamAssignment> teamRosters;
    private final ScoreAggregation scoreAggregation;
    private final WinCondition winCondition;
    private int roomProgress = 0;
    private final Map<String, Integer> teamProgress = new HashMap<>();
    private String gameOverReason = "";
    private String winnerId = "";

    private final Map<String, Long> lastSeenSequence = new HashMap<>();
    private final Map<String, GameMessage> lastAckByStudent = new HashMap<>();
    private final Map<String, Integer> totalScoreByStudent = new HashMap<>();

    private final Map<String, PlayerRecord> players = new LinkedHashMap<>();
    private final Set<String> dirtyStudentIds = new LinkedHashSet<>();
    private int nextStudentIndex = 0;
    private int flushesSinceFullSnapshot = 0;
    private long broadcastSeq = 0;
    private int questionsStartedCount = 0;

    private GamePhase phase = GamePhase.LOBBY;
    private String currentQuestionId;
    private List<String> currentCorrectAnswerIds = List.of();
    private long serverQuestionStartedAtMs;
    private long deadlineMs;

    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator) {
        this(roomId, clock, scoreCalculator, MissedStepPolicy.ZERO);
    }

    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy) {
        this(roomId, clock, scoreCalculator, missedStepPolicy, GameMode.GAME_MODE_SOLO, 0, List.of(),
                SharedResourceType.NONE, 0);
    }

    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty) {
        this(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, List.of(), ScoreAggregation.SUM_ALL,
                WinCondition.PROGRESS_COMPLETED);
    }

    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation) {
        this(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation, WinCondition.FIRST_TO_FINISH);
    }

    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy,
            GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
            SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition) {
        this.roomId = roomId;
        this.clock = clock;
        this.scoreCalculator = scoreCalculator;
        this.missedStepPolicy = missedStepPolicy;
        this.gameMode = gameMode;
        this.progressTarget = progressTarget;
        this.progressStages = progressStages;
        this.sharedResourceType = sharedResourceType;
        this.sharedResourcePenalty = sharedResourcePenalty;
        this.teamRosters = teamRosters;
        this.scoreAggregation = scoreAggregation;
        this.winCondition = winCondition;
    }

    byte[] serializeSnapshot() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF(phase.name());
            out.writeUTF(currentQuestionId == null ? "" : currentQuestionId);
            out.writeInt(currentCorrectAnswerIds.size());
            for (String answerId : currentCorrectAnswerIds) {
                out.writeUTF(answerId);
            }
            out.writeLong(serverQuestionStartedAtMs);
            out.writeLong(deadlineMs);
            out.writeInt(nextStudentIndex);
            out.writeInt(flushesSinceFullSnapshot);
            out.writeLong(broadcastSeq);
            out.writeInt(questionsStartedCount);

            out.writeInt(players.size());
            for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
                PlayerRecord player = entry.getValue();
                out.writeUTF(entry.getKey());
                out.writeInt(player.index);
                out.writeUTF(player.displayName);
                out.writeBoolean(player.answeredCurrent);
                out.writeBoolean(player.connected);
                out.writeInt(player.missedStepsAtJoin);
            }

            out.writeInt(totalScoreByStudent.size());
            for (Map.Entry<String, Integer> entry : totalScoreByStudent.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue());
            }

            out.writeInt(lastSeenSequence.size());
            for (Map.Entry<String, Long> entry : lastSeenSequence.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue());
            }

            out.writeInt(roomProgress);

            out.writeInt(teamProgress.size());
            for (Map.Entry<String, Integer> entry : teamProgress.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator, byte[] payload) {
        return restore(roomId, clock, scoreCalculator, MissedStepPolicy.ZERO, payload);
    }

    static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
            MissedStepPolicy missedStepPolicy, byte[] payload) {
        return restore(roomId, clock, scoreCalculator, missedStepPolicy, GameMode.GAME_MODE_SOLO, 0, List.of(),
                SharedResourceType.NONE, 0, payload);
    }

    static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
            MissedStepPolicy missedStepPolicy, GameMode gameMode, int progressTarget,
            List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
            byte[] payload) {
        return restore(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, List.of(), ScoreAggregation.SUM_ALL, payload);
    }

    static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
            MissedStepPolicy missedStepPolicy, GameMode gameMode, int progressTarget,
            List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, byte[] payload) {
        return restore(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation,
                WinCondition.FIRST_TO_FINISH, payload);
    }

    static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
            MissedStepPolicy missedStepPolicy, GameMode gameMode, int progressTarget,
            List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
            List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition,
            byte[] payload) {
        RoomState state = new RoomState(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget,
                progressStages, sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation, winCondition);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            state.phase = GamePhase.valueOf(in.readUTF());
            String questionId = in.readUTF();
            state.currentQuestionId = questionId.isEmpty() ? null : questionId;
            int correctAnswerCount = in.readInt();
            List<String> correctAnswerIds = new ArrayList<>(correctAnswerCount);
            for (int i = 0; i < correctAnswerCount; i++) {
                correctAnswerIds.add(in.readUTF());
            }
            state.currentCorrectAnswerIds = correctAnswerIds;
            state.serverQuestionStartedAtMs = in.readLong();
            state.deadlineMs = in.readLong();
            state.nextStudentIndex = in.readInt();
            state.flushesSinceFullSnapshot = in.readInt();
            state.broadcastSeq = in.readLong();
            state.questionsStartedCount = in.readInt();

            int playerCount = in.readInt();
            for (int i = 0; i < playerCount; i++) {
                String studentId = in.readUTF();
                int index = in.readInt();
                String displayName = in.readUTF();
                boolean answeredCurrent = in.readBoolean();
                boolean connected = in.readBoolean();
                int missedStepsAtJoin = in.readInt();
                PlayerRecord record = new PlayerRecord(index, displayName, missedStepsAtJoin);
                record.answeredCurrent = answeredCurrent;
                record.connected = connected;
                state.players.put(studentId, record);
            }

            int scoreCount = in.readInt();
            for (int i = 0; i < scoreCount; i++) {
                state.totalScoreByStudent.put(in.readUTF(), in.readInt());
            }

            int sequenceCount = in.readInt();
            for (int i = 0; i < sequenceCount; i++) {
                state.lastSeenSequence.put(in.readUTF(), in.readLong());
            }

            state.roomProgress = in.readInt(); // P2 Task 21

            int teamProgressCount = in.readInt(); // P2 Task 23
            for (int i = 0; i < teamProgressCount; i++) {
                state.teamProgress.put(in.readUTF(), in.readInt());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return state;
    }

    GamePhase phase() {
        return phase;
    }

    void startGame() {
        phase = GamePhase.PLAYING;
    }

    GameMessage joinRoom(String studentId, String displayName) {
        PlayerRecord record = players.computeIfAbsent(studentId, id -> {
            int missedSteps = questionsStartedCount;
            applyMissedStepPolicy(missedSteps);
            return new PlayerRecord(nextStudentIndex++, displayName, missedSteps);
        });
        record.connected = true;
        dirtyStudentIds.add(studentId);
        return buildFullSnapshot();
    }

    private void applyMissedStepPolicy(int missedSteps) {
        if (missedSteps == 0) {
            return;
        }
        switch (missedStepPolicy) {
            case ZERO -> {
                // Deliberate no-op: ZERO's whole effect IS the missed steps staying at their
                // natural default score. The point of this branch is that it's reached on
                // purpose, not that it changes anything.
            }
            case SKIP, ALLOW_LATE -> throw new IllegalStateException(
                    "RoomState given MissedStepPolicy." + missedStepPolicy + ", but only ZERO is "
                            + "implemented in Phase 1 -- DefinitionLoader/RoomActor.create should "
                            + "have rejected this before RoomState was ever constructed");
        }
    }

    int missedStepsFor(String studentId) {
        PlayerRecord record = players.get(studentId);
        return record == null ? 0 : record.missedStepsAtJoin;
    }

    void startQuestion(String questionId, long durationMs, List<String> correctAnswerIds) {
        this.currentQuestionId = questionId;
        this.currentCorrectAnswerIds = correctAnswerIds;
        this.serverQuestionStartedAtMs = clock.millis();
        this.deadlineMs = serverQuestionStartedAtMs + durationMs;
        this.questionsStartedCount++;

        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            if (entry.getValue().answeredCurrent) {
                entry.getValue().answeredCurrent = false;
                dirtyStudentIds.add(entry.getKey());
            }
        }
    }

    void endGame() {
        if (phase != GamePhase.FINISHED) {
            if (gameMode == GameMode.GAME_MODE_TEAM && winCondition == WinCondition.MOST_POINTS_WHEN_TIME_UP) {
                Map<String, Integer> scoreByTeam = new HashMap<>();
                teamRosters.forEach(roster -> scoreByTeam.put(roster.getTeamId(), computeTeamScore(roster)));
                gameOverReason = "most_points_when_time_up";
                winnerId = WinConditionEvaluator.singleHighestScorer(scoreByTeam).orElse("");
            } else {
                gameOverReason = "teacher_ended";
            }
            phase = GamePhase.FINISHED;
        }
    }

    GameMessage buildGameOver() {
        GameOver.Builder gameOver = GameOver.newBuilder()
                .setReason(gameOverReason)
                .setWinnerId(winnerId);
        players.keySet().forEach(studentId -> gameOver.addFinalStandings(buildPlayerState(studentId)));
        return GameMessage.newBuilder()
                .setType(MessageType.GAME_OVER)
                .setRoomId(roomId)
                .setGameOver(gameOver)
                .build();
    }


    GameMessage submitAnswer(String studentId, long sequence, String questionId, List<String> answerIds) {
        long serverReceivedAtMs = clock.millis();

        if (phase != GamePhase.PLAYING) {
            return buildAck(studentId, sequence, questionId, false, RejectReason.WRONG_PHASE,
                    0, totalScoreOf(studentId), serverReceivedAtMs, 0);
        }

        Long lastSeen = lastSeenSequence.get(studentId);
        if (lastSeen != null && sequence <= lastSeen) {
            GameMessage previousAck = lastAckByStudent.get(studentId);
            return Objects.requireNonNullElseGet(previousAck, () -> buildAck(studentId, sequence, questionId, false, RejectReason.DUPLICATE_SEQUENCE,
                    0, totalScoreOf(studentId), serverReceivedAtMs, 0));
        }

        if (!Objects.equals(questionId, currentQuestionId)) {
            GameMessage ack = buildAck(studentId, sequence, questionId, false, RejectReason.UNKNOWN_QUESTION,
                    0, totalScoreOf(studentId), serverReceivedAtMs, 0);
            remember(studentId, sequence, ack);
            return ack;
        }

        if (serverReceivedAtMs > deadlineMs + GRACE_MS) {
            GameMessage ack = buildAck(studentId, sequence, questionId, false, RejectReason.PAST_DEADLINE,
                    0, totalScoreOf(studentId), serverReceivedAtMs, 0);
            remember(studentId, sequence, ack);
            return ack;
        }

        long responseTimeMs = serverReceivedAtMs - serverQuestionStartedAtMs;
        int awarded = scoreCalculator.award(answerIds, currentCorrectAnswerIds, responseTimeMs);
        int newTotal = totalScoreByStudent.merge(studentId, awarded, Integer::sum);

        PlayerRecord record = players.get(studentId);
        if (record != null) {
            record.answeredCurrent = true;
            dirtyStudentIds.add(studentId);
        }

        boolean correct = isCorrectAnswer(answerIds, currentCorrectAnswerIds);
        if (gameMode == GameMode.GAME_MODE_COOPERATIVE) {
            applyCooperativeOutcome(correct);
        } else if (gameMode == GameMode.GAME_MODE_TEAM) {
            applyTeamOutcome(studentId, correct);
        }

        GameMessage ack = buildAck(studentId, sequence, questionId, true, RejectReason.NONE,
                awarded, newTotal, serverReceivedAtMs, responseTimeMs);
        remember(studentId, sequence, ack);
        return ack;
    }

    private void applyCooperativeOutcome(boolean correct) {
        if (correct) {
            roomProgress++;
            if (WinConditionEvaluator.progressCompleted(roomProgress, progressTarget)) {
                gameOverReason = "progress_completed";
                phase = GamePhase.FINISHED;
            }
        } else if (sharedResourceType == SharedResourceType.TIME) {
            deadlineMs -= sharedResourcePenalty * 1000L;
        }
    }

    private void applyTeamOutcome(String studentId, boolean correct) {
        if (!correct) {
            return;
        }
        String teamId = teamIdOf(studentId);
        if (teamId.isEmpty()) {
            return;
        }
        int newProgress = teamProgress.merge(teamId, 1, Integer::sum);
        if (winCondition == WinCondition.FIRST_TO_FINISH
                && WinConditionEvaluator.firstToFinish(newProgress, progressTarget)) {
            gameOverReason = "first_to_finish";
            winnerId = teamId;
            phase = GamePhase.FINISHED;
        }
    }

    private static boolean isCorrectAnswer(List<String> answerIds, List<String> correctAnswerIds) {
        return !answerIds.isEmpty() && Set.copyOf(answerIds).equals(Set.copyOf(correctAnswerIds));
    }

    GameMessage resyncSnapshot(String studentId) {
        return buildFullSnapshot().toBuilder().setStudentId(studentId).build();
    }

    boolean isDirty() {
        return !dirtyStudentIds.isEmpty();
    }

    Map<String, Long> lastSeenSequenceSnapshot() {
        return Map.copyOf(lastSeenSequence);
    }

    void markDisconnected(String studentId) {
        PlayerRecord record = players.get(studentId);
        if (record == null) {
            return;
        }
        record.connected = false;
        dirtyStudentIds.add(studentId);
    }

    static GameMessage buildStudentKicked(String roomId, String studentId) {
        return GameMessage.newBuilder()
                .setType(MessageType.STUDENT_KICKED)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .build();
    }

    static GameMessage buildCommittedSeq(String roomId, Map<String, Long> committedSequenceByStudent) {
        CommittedSeq.Builder committedSeq = CommittedSeq.newBuilder();
        committedSequenceByStudent.forEach((studentId, sequence) -> committedSeq.addCommitted(
                CommittedSeq.Entry.newBuilder().setStudentId(studentId).setSequence(sequence)));
        return GameMessage.newBuilder()
                .setType(MessageType.COMMITTED_SEQ)
                .setRoomId(roomId)
                .setCommittedSeq(committedSeq)
                .build();
    }

    GameMessage flush() {
        boolean sendFull = ++flushesSinceFullSnapshot >= FULL_SNAPSHOT_EVERY_N_FLUSHES;
        GameMessage message = sendFull ? buildFullSnapshot() : buildDeltaSnapshot();
        if (sendFull) {
            flushesSinceFullSnapshot = 0;
        }
        dirtyStudentIds.clear();
        return message;
    }

    private GameMessage buildFullSnapshot() {
        RoomStateSnapshot.Builder snapshot = baseSnapshotBuilder(true);
        players.keySet().forEach(studentId -> snapshot.addPlayers(buildPlayerState(studentId)));
        return buildSnapshotMessage(snapshot);
    }

    private GameMessage buildDeltaSnapshot() {
        RoomStateSnapshot.Builder snapshot = baseSnapshotBuilder(false);
        dirtyStudentIds.forEach(studentId -> snapshot.addPlayers(buildPlayerState(studentId)));
        return buildSnapshotMessage(snapshot);
    }

    private RoomStateSnapshot.Builder baseSnapshotBuilder(boolean full) {
        RoomStateSnapshot.Builder builder = RoomStateSnapshot.newBuilder()
                .setFull(full)
                .setPhase(phase)
                .setCurrentQuestionId(currentQuestionId == null ? "" : currentQuestionId)
                .setServerQuestionStartedAtMs(serverQuestionStartedAtMs)
                .setDeadlineMs(deadlineMs)
                .setGameMode(gameMode);
        if (gameMode == GameMode.GAME_MODE_COOPERATIVE) {
            builder.setProgress(buildProgressMeterSnapshot());
            if (sharedResourceType != SharedResourceType.NONE) {
                builder.setSharedResource(SharedResourceState.newBuilder()
                        .setResourceType(sharedResourceType.name()));
            }
        } else if (gameMode == GameMode.GAME_MODE_TEAM) {
            teamRosters.forEach(roster -> builder.addTeams(
                    roster.toBuilder().setTeamScore(computeTeamScore(roster)).build()));
        }
        return builder;
    }

    private int computeTeamScore(TeamAssignment roster) {
        int sum = roster.getStudentIdsList().stream().mapToInt(this::totalScoreOf).sum();
        int memberCount = roster.getStudentIdsList().size();
        return scoreAggregation == ScoreAggregation.AVERAGE && memberCount > 0 ? sum / memberCount : sum;
    }

    private String teamIdOf(String studentId) {
        return teamRosters.stream()
                .filter(roster -> roster.getStudentIdsList().contains(studentId))
                .findFirst()
                .map(TeamAssignment::getTeamId)
                .orElse("");
    }

    List<GameMessage> updateDraft(String senderId, String draftContent) {
        if (gameMode != GameMode.GAME_MODE_TEAM) {
            return List.of();
        }
        String teamId = teamIdOf(senderId);
        if (teamId.isEmpty()) {
            return List.of();
        }
        List<GameMessage> outbound = new ArrayList<>();
        for (TeamAssignment roster : teamRosters) {
            if (!roster.getTeamId().equals(teamId)) {
                continue;
            }
            for (String recipientId : roster.getStudentIdsList()) {
                if (recipientId.equals(senderId) || !players.containsKey(recipientId)) {
                    continue;
                }
                outbound.add(GameMessage.newBuilder()
                        .setType(MessageType.UPDATE_DRAFT)
                        .setRoomId(roomId)
                        .setStudentId(recipientId)
                        .setDraftUpdate(DraftUpdate.newBuilder()
                                .setTeamId(teamId)
                                .setStudentId(senderId)
                                .setDraftContent(draftContent))
                        .build());
            }
        }
        return outbound;
    }

    private ProgressMeterSnapshot buildProgressMeterSnapshot() {
        int percentage = progressTarget <= 0 ? 0 : (int) Math.floor(100.0 * roomProgress / progressTarget);
        return ProgressMeterSnapshot.newBuilder()
                .setCurrentProgress(roomProgress)
                .setTargetProgress(progressTarget)
                .setProgressPercentage(percentage)
                .setStageIndex(stageIndexFor(percentage))
                .build();
    }

    private int stageIndexFor(int percentage) {
        int stageIndex = 0;
        for (int i = 0; i < progressStages.size(); i++) {
            if (progressStages.get(i).milestonePercent() > percentage) {
                break;
            }
            stageIndex = i;
        }
        return stageIndex;
    }

    private GameMessage buildSnapshotMessage(RoomStateSnapshot.Builder snapshot) {
        snapshot.setBroadcastSeq(++broadcastSeq);
        return GameMessage.newBuilder()
                .setType(MessageType.ROOM_STATE_SNAPSHOT)
                .setRoomId(roomId)
                .setRoomStateSnapshot(snapshot)
                .build();
    }

    private PlayerState buildPlayerState(String studentId) {
        PlayerRecord record = players.get(studentId);
        PlayerState.Builder builder = PlayerState.newBuilder()
                .setStudentId(studentId)
                .setStudentIndex(record.index)
                .setDisplayName(record.displayName)
                .setScore(totalScoreOf(studentId))
                .setAnsweredCurrent(record.answeredCurrent)
                .setConnected(record.connected);
        if (gameMode == GameMode.GAME_MODE_TEAM) {
            builder.setTeamId(teamIdOf(studentId));
        }
        return builder.build();
    }

    private void remember(String studentId, long sequence, GameMessage ack) {
        lastSeenSequence.put(studentId, sequence);
        lastAckByStudent.put(studentId, ack);
    }

    private int totalScoreOf(String studentId) {
        return totalScoreByStudent.getOrDefault(studentId, 0);
    }

    private GameMessage buildAck(String studentId, long sequence, String questionId, boolean accepted,
            RejectReason reason, int awardedPoints, int totalScore, long serverReceivedAtMs, long responseTimeMs) {
        AnswerAck ack = AnswerAck.newBuilder()
                .setQuestionId(questionId == null ? "" : questionId)
                .setAckedSequence(sequence)
                .setAccepted(accepted)
                .setRejectReason(reason)
                .setAwardedPoints(awardedPoints)
                .setTotalScore(totalScore)
                .setServerReceivedAtMs(serverReceivedAtMs)
                .setResponseTimeMs((int) Math.max(0, responseTimeMs))
                .build();

        return GameMessage.newBuilder()
                .setType(MessageType.ANSWER_ACK)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .setSequence(sequence)
                .setAnswerAck(ack)
                .build();
    }

    private static final class PlayerRecord {
        private final int index;
        private final String displayName;
        private final int missedStepsAtJoin;
        private boolean answeredCurrent;
        private boolean connected;

        PlayerRecord(int index, String displayName, int missedStepsAtJoin) {
            this.index = index;
            this.displayName = displayName;
            this.missedStepsAtJoin = missedStepsAtJoin;
        }
    }
}
