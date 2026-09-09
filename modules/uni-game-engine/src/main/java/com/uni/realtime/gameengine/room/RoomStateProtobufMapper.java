package com.uni.realtime.gameengine.room;

import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameOver;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.PlayerState;
import com.uni.realtime.protocol.RejectReason;

public final class RoomStateProtobufMapper {

    private RoomStateProtobufMapper() {}

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
}
