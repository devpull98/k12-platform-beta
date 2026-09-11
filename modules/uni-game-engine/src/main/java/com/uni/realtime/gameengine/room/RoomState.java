package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.GameDefinition;
import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.gameengine.domain.game.GameModeRules;
import com.uni.realtime.gameengine.domain.game.GameModeRulesFactory;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.CommittedSeq;
import com.uni.realtime.protocol.DraftUpdate;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.PlayerState;
import com.uni.realtime.protocol.ProgressMeterSnapshot;
import com.uni.realtime.protocol.RejectReason;
import com.uni.realtime.protocol.RoomStateSnapshot;
import com.uni.realtime.protocol.SharedResourceState;
import com.uni.realtime.protocol.TeamAssignment;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.uni.realtime.gameengine.domain.game.GameRuleContext;
import com.uni.realtime.gameengine.domain.game.GameModeRules;
import com.uni.realtime.gameengine.domain.game.GameModeRulesFactory;

public final class RoomState implements GameRuleContext {

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
    private final GameModeRules modeRules;

    private int roomProgress = 0;
    private final Map<String, Integer> teamProgress = new HashMap<>();
    private String gameOverReason = "";
    private String winnerId = "";

    private final Map<String, Long> teamResponseTimeMs = new HashMap<>();
    private final Set<String> teamsRespondedToCurrentQuestion = new HashSet<>();

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

    public RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator) {
        this(roomId, clock, scoreCalculator, MissedStepPolicy.ZERO, GameDefinition.defaultSoloDefinition());
    }

    public RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy) {
        this(roomId, clock, scoreCalculator, missedStepPolicy, GameDefinition.defaultSoloDefinition());
    }

    public RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy,
                     GameDefinition gameDefinition) {
        this(roomId, clock, scoreCalculator, missedStepPolicy,
                gameDefinition != null ? gameDefinition.gameMode() : GameMode.GAME_MODE_SOLO,
                gameDefinition != null ? gameDefinition.progressTarget() : 0,
                gameDefinition != null ? gameDefinition.progressStages() : List.of(),
                gameDefinition != null ? gameDefinition.sharedResourceType() : SharedResourceType.NONE,
                gameDefinition != null ? gameDefinition.sharedResourcePenalty() : 0,
                gameDefinition != null ? gameDefinition.teamRosters() : List.of(),
                gameDefinition != null ? gameDefinition.scoreAggregation() : ScoreAggregation.SUM_ALL,
                gameDefinition != null ? gameDefinition.winCondition() : WinCondition.PROGRESS_COMPLETED);
    }

    public RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy,
                     GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
                     SharedResourceType sharedResourceType, int sharedResourcePenalty) {
        this(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, List.of(), ScoreAggregation.SUM_ALL,
                WinCondition.PROGRESS_COMPLETED);
    }

    public RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy,
                     GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
                     SharedResourceType sharedResourceType, int sharedResourcePenalty,
                     List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation) {
        this(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation, WinCondition.FIRST_TO_FINISH);
    }

    public RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy,
                     GameMode gameMode, int progressTarget, List<ProgressStage> progressStages,
                     SharedResourceType sharedResourceType, int sharedResourcePenalty,
                     List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition) {
        this.roomId = roomId;
        this.clock = clock;
        this.scoreCalculator = scoreCalculator;
        this.missedStepPolicy = missedStepPolicy;
        this.gameMode = gameMode != null ? gameMode : GameMode.GAME_MODE_SOLO;
        this.progressTarget = progressTarget;
        this.progressStages = progressStages != null ? progressStages : List.of();
        this.sharedResourceType = sharedResourceType != null ? sharedResourceType : SharedResourceType.NONE;
        this.sharedResourcePenalty = sharedResourcePenalty;
        this.teamRosters = teamRosters != null ? teamRosters : List.of();
        this.scoreAggregation = scoreAggregation != null ? scoreAggregation : ScoreAggregation.SUM_ALL;
        this.winCondition = winCondition != null ? winCondition : WinCondition.PROGRESS_COMPLETED;
        this.modeRules = GameModeRulesFactory.forMode(this.gameMode);
    }

    public byte[] serializeSnapshot() {
        return RoomStateSerializer.serialize(this);
    }

    public static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator, byte[] payload) {
        return RoomStateSerializer.restore(roomId, clock, scoreCalculator, MissedStepPolicy.ZERO, GameDefinition.defaultSoloDefinition(), payload);
    }

    public static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
                                    MissedStepPolicy missedStepPolicy, byte[] payload) {
        return RoomStateSerializer.restore(roomId, clock, scoreCalculator, missedStepPolicy, GameDefinition.defaultSoloDefinition(), payload);
    }

    public static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
                                    MissedStepPolicy missedStepPolicy, GameMode gameMode, int progressTarget,
                                    List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
                                    byte[] payload) {
        return restore(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, List.of(), ScoreAggregation.SUM_ALL, WinCondition.PROGRESS_COMPLETED, payload);
    }

    public static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
                                    MissedStepPolicy missedStepPolicy, GameMode gameMode, int progressTarget,
                                    List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
                                    List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, byte[] payload) {
        return restore(roomId, clock, scoreCalculator, missedStepPolicy, gameMode, progressTarget, progressStages,
                sharedResourceType, sharedResourcePenalty, teamRosters, scoreAggregation, WinCondition.FIRST_TO_FINISH, payload);
    }

    public static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
                                    MissedStepPolicy missedStepPolicy, GameMode gameMode, int progressTarget,
                                    List<ProgressStage> progressStages, SharedResourceType sharedResourceType, int sharedResourcePenalty,
                                    List<TeamAssignment> teamRosters, ScoreAggregation scoreAggregation, WinCondition winCondition,
                                    byte[] payload) {
        GameDefinition def = new GameDefinition(gameMode, progressTarget, progressStages, sharedResourceType,
                sharedResourcePenalty, teamRosters, scoreAggregation, winCondition);
        return RoomStateSerializer.restore(roomId, clock, scoreCalculator, missedStepPolicy, def, payload);
    }

    public String roomId() { return roomId; }
    public GamePhase phase() { return phase; }
    public GameMode gameMode() { return gameMode; }
    public int progressTarget() { return progressTarget; }
    public List<ProgressStage> progressStages() { return progressStages; }
    public SharedResourceType sharedResourceType() { return sharedResourceType; }
    public int sharedResourcePenalty() { return sharedResourcePenalty; }
    public List<TeamAssignment> teamRosters() { return teamRosters; }
    public ScoreAggregation scoreAggregation() { return scoreAggregation; }
    public WinCondition winCondition() { return winCondition; }
    public int roomProgress() { return roomProgress; }
    public String gameOverReason() { return gameOverReason; }
    public String winnerId() { return winnerId; }
    public String currentQuestionId() { return currentQuestionId; }
    public List<String> currentCorrectAnswerIds() { return currentCorrectAnswerIds; }
    public long serverQuestionStartedAtMs() { return serverQuestionStartedAtMs; }
    public long deadlineMs() { return deadlineMs; }
    public int nextStudentIndex() { return nextStudentIndex; }
    public int flushesSinceFullSnapshot() { return flushesSinceFullSnapshot; }
    public long broadcastSeq() { return broadcastSeq; }
    public int questionsStartedCount() { return questionsStartedCount; }
    public Map<String, PlayerRecord> playersMap() { return players; }
    public Map<String, Integer> scoresMap() { return totalScoreByStudent; }
    public Map<String, Long> lastSeenSequenceMap() { return lastSeenSequence; }
    public Map<String, Integer> teamProgressMap() { return teamProgress; }
    public Map<String, Long> teamResponseTimeMsMap() { return teamResponseTimeMs; }

    public void restorePhase(GamePhase phase) { this.phase = phase; }
    public void restoreCurrentQuestionId(String questionId) { this.currentQuestionId = questionId; }
    public void restoreCurrentCorrectAnswerIds(List<String> answerIds) { this.currentCorrectAnswerIds = answerIds; }
    public void restoreServerQuestionStartedAtMs(long startedAt) { this.serverQuestionStartedAtMs = startedAt; }
    public void restoreDeadlineMs(long deadline) { this.deadlineMs = deadline; }
    public void restoreNextStudentIndex(int index) { this.nextStudentIndex = index; }
    public void restoreFlushesSinceFullSnapshot(int count) { this.flushesSinceFullSnapshot = count; }
    public void restoreBroadcastSeq(long seq) { this.broadcastSeq = seq; }
    public void restoreQuestionsStartedCount(int count) { this.questionsStartedCount = count; }
    public void restoreRoomProgress(int progress) { this.roomProgress = progress; }

    public int incrementRoomProgress() {
        return ++this.roomProgress;
    }

    public int incrementTeamProgress(String teamId) {
        return this.teamProgress.merge(teamId, 1, Integer::sum);
    }

    public void reduceDeadlineMs(long penaltyMs) {
        this.deadlineMs -= penaltyMs;
    }

    public void setGameOver(String reason, String winnerId) {
        this.gameOverReason = reason;
        this.winnerId = winnerId;
        this.phase = GamePhase.FINISHED;
    }

    public void startGame() {
        phase = GamePhase.PLAYING;
    }

    public GameMessage joinRoom(String studentId, String displayName) {
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
            case ZERO -> {}
            case SKIP, ALLOW_LATE -> throw new IllegalStateException(
                    "RoomState given MissedStepPolicy." + missedStepPolicy + ", but only ZERO is "
                            + "implemented in Phase 1 -- DefinitionLoader/RoomActor.create should "
                            + "have rejected this before RoomState was ever constructed");
        }
    }

    public int missedStepsFor(String studentId) {
        PlayerRecord record = players.get(studentId);
        return record == null ? 0 : record.missedStepsAtJoin;
    }

    public void startQuestion(String questionId, long durationMs, List<String> correctAnswerIds) {
        if (currentQuestionId != null) {
            modeRules.finalizeQuestionOutcome(this);
        }

        this.currentQuestionId = questionId;
        this.currentCorrectAnswerIds = correctAnswerIds;
        this.serverQuestionStartedAtMs = clock.millis();
        this.deadlineMs = serverQuestionStartedAtMs + durationMs;
        this.questionsStartedCount++;
        this.teamsRespondedToCurrentQuestion.clear();

        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            entry.getValue().correctCurrent = false;
            if (entry.getValue().answeredCurrent) {
                entry.getValue().answeredCurrent = false;
                dirtyStudentIds.add(entry.getKey());
            }
        }
    }

    public void endGame() {
        if (phase != GamePhase.FINISHED) {
            modeRules.finalizeQuestionOutcome(this);
        }
        if (phase != GamePhase.FINISHED) {
            modeRules.evaluateTimeUp(this);
        }
    }

    public GameMessage buildGameOver() {
        return RoomStateProtobufMapper.buildGameOver(this);
    }

    public GameMessage submitAnswer(String studentId, long sequence, String questionId, List<String> answerIds) {
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

        String respondingTeamId = teamIdOf(studentId);
        if (!respondingTeamId.isEmpty() && teamsRespondedToCurrentQuestion.add(respondingTeamId)) {
            teamResponseTimeMs.merge(respondingTeamId, responseTimeMs, Long::sum);
        }

        int awarded = scoreCalculator.award(answerIds, currentCorrectAnswerIds, responseTimeMs);
        int newTotal = totalScoreByStudent.merge(studentId, awarded, Integer::sum);

        boolean correct = isCorrectAnswer(answerIds, currentCorrectAnswerIds);

        PlayerRecord record = players.get(studentId);
        if (record != null) {
            record.answeredCurrent = true;
            record.correctCurrent = correct;
            record.hasEverAnswered = true;
            dirtyStudentIds.add(studentId);
        }

        modeRules.applyAnswerOutcome(this, studentId, correct);

        GameMessage ack = buildAck(studentId, sequence, questionId, true, RejectReason.NONE,
                awarded, newTotal, serverReceivedAtMs, responseTimeMs);
        remember(studentId, sequence, ack);
        return ack;
    }

    private static boolean isCorrectAnswer(List<String> answerIds, List<String> correctAnswerIds) {
        return !answerIds.isEmpty() && Set.copyOf(answerIds).equals(Set.copyOf(correctAnswerIds));
    }

    public GameMessage resyncSnapshot(String studentId) {
        return buildFullSnapshot().toBuilder().setStudentId(studentId).build();
    }

    public boolean isDirty() {
        return !dirtyStudentIds.isEmpty();
    }

    public Map<String, Long> lastSeenSequenceSnapshot() {
        return Map.copyOf(lastSeenSequence);
    }

    public void markDisconnected(String studentId) {
        PlayerRecord record = players.get(studentId);
        if (record == null) {
            return;
        }
        record.connected = false;
        dirtyStudentIds.add(studentId);
    }

    public static GameMessage buildStudentKicked(String roomId, String studentId) {
        return GameMessage.newBuilder()
                .setType(MessageType.STUDENT_KICKED)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .build();
    }

    public static GameMessage buildCommittedSeq(String roomId, Map<String, Long> committedSequenceByStudent) {
        CommittedSeq.Builder committedSeq = CommittedSeq.newBuilder();
        committedSequenceByStudent.forEach((studentId, sequence) -> committedSeq.addCommitted(
                CommittedSeq.Entry.newBuilder().setStudentId(studentId).setSequence(sequence)));
        return GameMessage.newBuilder()
                .setType(MessageType.COMMITTED_SEQ)
                .setRoomId(roomId)
                .setCommittedSeq(committedSeq)
                .build();
    }

    public GameMessage flush() {
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

    public int computeTeamScore(TeamAssignment roster) {
        int sum = roster.getStudentIdsList().stream().mapToInt(this::totalScoreOf).sum();
        int memberCount = roster.getStudentIdsList().size();
        return scoreAggregation == ScoreAggregation.AVERAGE && memberCount > 0 ? sum / memberCount : sum;
    }

    public long teamResponseTimeMs(String teamId) {
        return teamResponseTimeMs.getOrDefault(teamId, 0L);
    }

    public boolean hasAnsweredCurrentQuestion(String studentId) {
        PlayerRecord record = players.get(studentId);
        return record != null && record.answeredCurrent;
    }

    public boolean hasAnsweredCurrentQuestionCorrectly(String studentId) {
        PlayerRecord record = players.get(studentId);
        return record != null && record.correctCurrent;
    }

    public String teamIdOf(String studentId) {
        return teamRosters.stream()
                .filter(roster -> roster.getStudentIdsList().contains(studentId))
                .findFirst()
                .map(TeamAssignment::getTeamId)
                .orElse("");
    }

    public List<GameMessage> updateDraft(String senderId, String draftContent) {
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

    public int totalScoreOf(String studentId) {
        return totalScoreByStudent.getOrDefault(studentId, 0);
    }

    private GameMessage buildAck(String studentId, long sequence, String questionId, boolean accepted,
                                 RejectReason reason, int awardedPoints, int totalScore, long serverReceivedAtMs, long responseTimeMs) {
        return RoomStateProtobufMapper.buildAck(roomId, studentId, sequence, questionId, accepted, reason,
                awardedPoints, totalScore, (int) Math.max(0, responseTimeMs), serverReceivedAtMs);
    }
}
