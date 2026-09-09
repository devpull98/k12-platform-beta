package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.ProgressStage;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameOver;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.PlayerState;
import com.uni.realtime.protocol.ProgressMeterSnapshot;
import com.uni.realtime.protocol.RejectReason;
import com.uni.realtime.protocol.RoomStateSnapshot;
import com.uni.realtime.protocol.SharedResourceState;

import java.util.List;
import java.util.Map;

public final class RoomStateProtobufMapper {

    private RoomStateProtobufMapper() {}

    public static GameMessage buildFullSnapshot(RoomState state) {
        RoomStateSnapshot.Builder snapshot = RoomStateSnapshot.newBuilder()
                .setFull(true)
                .setPhase(state.phase())
                .setCurrentQuestionId(state.currentQuestionId() == null ? "" : state.currentQuestionId())
                .setServerQuestionStartedAtMs(state.serverQuestionStartedAtMs())
                .setDeadlineMs(state.deadlineMs())
                .setBroadcastSeq(state.broadcastSeq())
                .setGameMode(state.gameMode())
                .addAllTeams(state.teamRosters());

        for (Map.Entry<String, PlayerRecord> entry : state.playersMap().entrySet()) {
            snapshot.addPlayers(buildPlayerState(state, entry.getKey(), entry.getValue()));
        }

        if (state.progressTarget() > 0) {
            int percentage = Math.min(100, (int) Math.round(((double) state.roomProgress() / state.progressTarget()) * 100.0));
            int stageIndex = computeStageIndex(state.progressStages(), percentage);
            snapshot.setProgress(ProgressMeterSnapshot.newBuilder()
                    .setCurrentProgress(state.roomProgress())
                    .setTargetProgress(state.progressTarget())
                    .setProgressPercentage(percentage)
                    .setStageIndex(stageIndex)
                    .build());
        }

        if (state.sharedResourceType() != SharedResourceType.NONE) {
            snapshot.setSharedResource(SharedResourceState.newBuilder()
                    .setResourceType(state.sharedResourceType().name())
                    .setRemainingLives(0)
                    .build());
        }

        return GameMessage.newBuilder()
                .setType(MessageType.ROOM_STATE_SNAPSHOT)
                .setRoomId(state.roomId())
                .setRoomStateSnapshot(snapshot)
                .build();
    }

    public static PlayerState buildPlayerState(RoomState state, String studentId, PlayerRecord record) {
        return PlayerState.newBuilder()
                .setStudentId(studentId)
                .setStudentIndex(record.index)
                .setDisplayName(record.displayName)
                .setScore(state.totalScoreOf(studentId))
                .setAnsweredCurrent(record.answeredCurrent)
                .setConnected(record.connected)
                .setTeamId(state.teamIdOf(studentId))
                .build();
    }

    public static GameMessage buildAck(String roomId, String studentId, long sequence, String questionId,
                                       boolean accepted, RejectReason rejectReason, int awardedPoints,
                                       int totalScore, int responseTimeMs, long serverReceivedAtMs) {
        AnswerAck ack = AnswerAck.newBuilder()
                .setQuestionId(questionId)
                .setAckedSequence(sequence)
                .setAccepted(accepted)
                .setRejectReason(rejectReason)
                .setAwardedPoints(awardedPoints)
                .setTotalScore(totalScore)
                .setServerReceivedAtMs(serverReceivedAtMs)
                .setResponseTimeMs(responseTimeMs)
                .build();

        return GameMessage.newBuilder()
                .setType(MessageType.ANSWER_ACK)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .setSequence(sequence)
                .setAnswerAck(ack)
                .build();
    }

    public static GameMessage buildGameOver(RoomState state) {
        GameOver.Builder gameOver = GameOver.newBuilder()
                .setReason(state.gameOverReason())
                .setWinnerId(state.winnerId());
        state.playersMap().forEach((studentId, record) ->
                gameOver.addFinalStandings(buildPlayerState(state, studentId, record)));

        return GameMessage.newBuilder()
                .setType(MessageType.GAME_OVER)
                .setRoomId(state.roomId())
                .setGameOver(gameOver)
                .build();
    }

    private static int computeStageIndex(List<ProgressStage> stages, int percentage) {
        int index = 0;
        for (int i = 0; i < stages.size(); i++) {
            if (percentage >= stages.get(i).milestonePercent()) {
                index = i;
            }
        }
        return index;
    }
}
