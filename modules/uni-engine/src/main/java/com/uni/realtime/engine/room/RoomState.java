package com.uni.realtime.engine.room;

import com.uni.realtime.engine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.RejectReason;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory state of a single room, owned exclusively by its {@link RoomActor}. Every rule
 * here traces to system-architecture.md §5.1 (server-authoritative timestamp) and §5.2
 * (two-tier dedupe) — this class is deliberately Pekko-free so those rules stay testable
 * without an actor system.
 */
final class RoomState {

    private static final long GRACE_MS = 500;

    private final String roomId;
    private final Clock clock;
    private final ScoreCalculator scoreCalculator;

    private final Map<String, Long> lastSeenSequence = new HashMap<>();
    private final Map<String, GameMessage> lastAckByStudent = new HashMap<>();
    private final Map<String, Integer> totalScoreByStudent = new HashMap<>();

    private GamePhase phase = GamePhase.LOBBY;
    private String currentQuestionId;
    private long serverQuestionStartedAtMs;
    private long deadlineMs;

    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator) {
        this.roomId = roomId;
        this.clock = clock;
        this.scoreCalculator = scoreCalculator;
    }

    GamePhase phase() {
        return phase;
    }

    void startGame() {
        phase = GamePhase.PLAYING;
    }

    void startQuestion(String questionId, long durationMs) {
        this.currentQuestionId = questionId;
        this.serverQuestionStartedAtMs = clock.millis();
        this.deadlineMs = serverQuestionStartedAtMs + durationMs;
    }

    void endGame() {
        phase = GamePhase.FINISHED;
    }

    /**
     * Rule 1 (§5.1): the receive timestamp is stamped here, first, before the phase check or
     * any table lookup — not after validation decides the submission is worth timing.
     */
    GameMessage submitAnswer(String studentId, long sequence, String questionId, List<String> answerIds) {
        long serverReceivedAtMs = clock.millis();

        if (phase != GamePhase.PLAYING) {
            return buildAck(studentId, sequence, questionId, false, RejectReason.WRONG_PHASE,
                    0, totalScoreOf(studentId), serverReceivedAtMs, 0);
        }

        Long lastSeen = lastSeenSequence.get(studentId);
        if (lastSeen != null && sequence <= lastSeen) {
            // §5.2: replay the ORIGINAL ack verbatim. Never recompute -- the score it quoted
            // may no longer match totalScoreByStudent if later submissions changed it.
            GameMessage previousAck = lastAckByStudent.get(studentId);
            if (previousAck != null) {
                return previousAck;
            }
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
        int awarded = scoreCalculator.award(answerIds, responseTimeMs);
        int newTotal = totalScoreByStudent.merge(studentId, awarded, Integer::sum);

        GameMessage ack = buildAck(studentId, sequence, questionId, true, RejectReason.NONE,
                awarded, newTotal, serverReceivedAtMs, responseTimeMs);
        remember(studentId, sequence, ack);
        return ack;
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
}
