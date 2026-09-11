package com.uni.realtime.gameengine.room;

import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GameMode;
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
                .setTrophies(computeTrophies(state, studentId, record))
                .build();
    }

    /**
     * PO V2.2 SS5.2 (P2 Task 32): trophies = the student's TEAM score (1 point = 1 trophy), only
     * for GAME_MODE_TEAM, and only if the student answered >=1 question anywhere in the game (SS6
     * rule 4 -- a student who quit/disconnected without ever answering gets none, but one who did
     * still gets the team's full trophy count even after quitting). This method is only reachable
     * via {@link #buildPlayerState}, which only {@link #buildGameOver} calls -- {@code trophies}
     * has no meaning mid-game, so it is never computed for the live snapshot path
     * ({@code RoomState}'s own private {@code buildPlayerState(String)}).
     */
    private static int computeTrophies(RoomState state, String studentId, PlayerRecord record) {
        if (state.gameMode() != GameMode.GAME_MODE_TEAM || !record.hasEverAnswered) {
            return 0;
        }
        String teamId = state.teamIdOf(studentId);
        return state.teamRosters().stream()
                .filter(roster -> roster.getTeamId().equals(teamId))
                .findFirst()
                .map(state::computeTeamScore)
                .orElse(0);
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
