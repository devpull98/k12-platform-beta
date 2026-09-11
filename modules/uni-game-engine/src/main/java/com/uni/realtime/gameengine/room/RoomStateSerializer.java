package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.GameDefinition;
import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RoomStateSerializer {

    private RoomStateSerializer() {}

    public static byte[] serialize(RoomState state) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF(state.phase().name());
            out.writeUTF(state.currentQuestionId() == null ? "" : state.currentQuestionId());
            
            List<String> correctAnswers = state.currentCorrectAnswerIds();
            out.writeInt(correctAnswers.size());
            for (String answerId : correctAnswers) {
                out.writeUTF(answerId);
            }
            
            out.writeLong(state.serverQuestionStartedAtMs());
            out.writeLong(state.deadlineMs());
            out.writeInt(state.nextStudentIndex());
            out.writeInt(state.flushesSinceFullSnapshot());
            out.writeLong(state.broadcastSeq());
            out.writeInt(state.questionsStartedCount());

            Map<String, PlayerRecord> players = state.playersMap();
            out.writeInt(players.size());
            for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
                PlayerRecord player = entry.getValue();
                out.writeUTF(entry.getKey());
                out.writeInt(player.index);
                out.writeUTF(player.displayName);
                out.writeBoolean(player.answeredCurrent);
                out.writeBoolean(player.connected);
                out.writeInt(player.missedStepsAtJoin);
                out.writeBoolean(player.correctCurrent);
            }

            Map<String, Integer> scores = state.scoresMap();
            out.writeInt(scores.size());
            for (Map.Entry<String, Integer> entry : scores.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue());
            }

            Map<String, Long> sequences = state.lastSeenSequenceSnapshot();
            out.writeInt(sequences.size());
            for (Map.Entry<String, Long> entry : sequences.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue());
            }

            out.writeInt(state.roomProgress());

            Map<String, Integer> teamProgress = state.teamProgressMap();
            out.writeInt(teamProgress.size());
            for (Map.Entry<String, Integer> entry : teamProgress.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue());
            }

            Map<String, Long> teamResponseTimeMs = state.teamResponseTimeMsMap();
            out.writeInt(teamResponseTimeMs.size());
            for (Map.Entry<String, Long> entry : teamResponseTimeMs.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    public static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
                                    MissedStepPolicy missedStepPolicy, GameDefinition gameDefinition,
                                    byte[] payload) {
        RoomState state = new RoomState(roomId, clock, scoreCalculator, missedStepPolicy, gameDefinition);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            state.restorePhase(com.uni.realtime.protocol.GamePhase.valueOf(in.readUTF()));
            String questionId = in.readUTF();
            state.restoreCurrentQuestionId(questionId.isEmpty() ? null : questionId);
            
            int correctAnswerCount = in.readInt();
            List<String> correctAnswerIds = new ArrayList<>(correctAnswerCount);
            for (int i = 0; i < correctAnswerCount; i++) {
                correctAnswerIds.add(in.readUTF());
            }
            state.restoreCurrentCorrectAnswerIds(correctAnswerIds);
            
            state.restoreServerQuestionStartedAtMs(in.readLong());
            state.restoreDeadlineMs(in.readLong());
            state.restoreNextStudentIndex(in.readInt());
            state.restoreFlushesSinceFullSnapshot(in.readInt());
            state.restoreBroadcastSeq(in.readLong());
            state.restoreQuestionsStartedCount(in.readInt());

            int playerCount = in.readInt();
            for (int i = 0; i < playerCount; i++) {
                String studentId = in.readUTF();
                int index = in.readInt();
                String displayName = in.readUTF();
                boolean answeredCurrent = in.readBoolean();
                boolean connected = in.readBoolean();
                int missedStepsAtJoin = in.readInt();
                boolean correctCurrent = in.readBoolean();
                PlayerRecord record = new PlayerRecord(index, displayName, missedStepsAtJoin);
                record.answeredCurrent = answeredCurrent;
                record.connected = connected;
                record.correctCurrent = correctCurrent;
                state.playersMap().put(studentId, record);
            }

            int scoreCount = in.readInt();
            for (int i = 0; i < scoreCount; i++) {
                state.scoresMap().put(in.readUTF(), in.readInt());
            }

            int sequenceCount = in.readInt();
            for (int i = 0; i < sequenceCount; i++) {
                state.lastSeenSequenceMap().put(in.readUTF(), in.readLong());
            }

            state.restoreRoomProgress(in.readInt());

            int teamProgressCount = in.readInt();
            for (int i = 0; i < teamProgressCount; i++) {
                state.teamProgressMap().put(in.readUTF(), in.readInt());
            }

            int teamResponseTimeCount = in.readInt();
            for (int i = 0; i < teamResponseTimeCount; i++) {
                state.teamResponseTimeMsMap().put(in.readUTF(), in.readLong());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return state;
    }
}
