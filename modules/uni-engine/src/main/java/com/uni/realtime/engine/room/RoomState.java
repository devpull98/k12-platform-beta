package com.uni.realtime.engine.room;

import com.uni.realtime.engine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.PlayerState;
import com.uni.realtime.protocol.RejectReason;
import com.uni.realtime.protocol.RoomStateSnapshot;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory state of a single room, owned exclusively by its {@link RoomActor}. Every rule
 * here traces to system-architecture.md §5.1 (server-authoritative timestamp) and §5.2
 * (two-tier dedupe) — this class is deliberately Pekko-free so those rules stay testable
 * without an actor system.
 */
final class RoomState {

    private static final long GRACE_MS = 500;

    /**
     * G2a (tech-design.md §9.1, chốt 2026-09-06): cứ 10 lần flush thì gửi một full snapshot
     * thay vì delta -- lưới an toàn cho một client bị drop một delta best-effort dưới
     * backpressure (§5.4), vì PH-3 (client resync thật) chưa tồn tại.
     */
    private static final int FULL_SNAPSHOT_EVERY_N_FLUSHES = 10;

    private final String roomId;
    private final Clock clock;
    private final ScoreCalculator scoreCalculator;

    private final Map<String, Long> lastSeenSequence = new HashMap<>();
    private final Map<String, GameMessage> lastAckByStudent = new HashMap<>();
    private final Map<String, Integer> totalScoreByStudent = new HashMap<>();

    /** Roster, keyed by join order via {@link PlayerRecord#index} (§3.6: index, not UUID, on the wire). */
    private final Map<String, PlayerRecord> players = new LinkedHashMap<>();
    /** Student ids with roster state changed since the last flush -- the "dirty" of ADR-4. */
    private final Set<String> dirtyStudentIds = new LinkedHashSet<>();
    private int nextStudentIndex = 0;
    private int flushesSinceFullSnapshot = 0;

    private GamePhase phase = GamePhase.LOBBY;
    private String currentQuestionId;
    private List<String> currentCorrectAnswerIds = List.of();
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

    /**
     * Full snapshot on join (§6.2) -- the joiner needs the whole room, not a delta built for
     * players who were already there. Rejoin with the same {@code studentId} keeps its
     * original {@code student_index}: identity in the delta wire format depends on that index
     * staying stable (§G2 D4).
     */
    GameMessage joinRoom(String studentId, String displayName) {
        PlayerRecord record = players.computeIfAbsent(studentId, id -> new PlayerRecord(nextStudentIndex++, displayName));
        record.connected = true;
        dirtyStudentIds.add(studentId);
        return buildFullSnapshot();
    }

    void startQuestion(String questionId, long durationMs, List<String> correctAnswerIds) {
        this.currentQuestionId = questionId;
        this.currentCorrectAnswerIds = correctAnswerIds;
        this.serverQuestionStartedAtMs = clock.millis();
        this.deadlineMs = serverQuestionStartedAtMs + durationMs;

        // A new question invalidates "already answered" from the previous one -- flip it back
        // and let the next coalescing flush carry that change out (no separate broadcast here;
        // QUESTION_STARTED itself is Critical and out of RoomState's scope, see plan.md Task 13).
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            if (entry.getValue().answeredCurrent) {
                entry.getValue().answeredCurrent = false;
                dirtyStudentIds.add(entry.getKey());
            }
        }
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
        int awarded = scoreCalculator.award(answerIds, currentCorrectAnswerIds, responseTimeMs);
        int newTotal = totalScoreByStudent.merge(studentId, awarded, Integer::sum);

        // A genuinely new, on-time submission changes roster state (score, answered_current)
        // -- mark dirty so the next coalescing flush carries it (§6.2). A replay above never
        // reaches here, so it never re-dirties the room.
        PlayerRecord record = players.get(studentId);
        if (record != null) {
            record.answeredCurrent = true;
            dirtyStudentIds.add(studentId);
        }

        GameMessage ack = buildAck(studentId, sequence, questionId, true, RejectReason.NONE,
                awarded, newTotal, serverReceivedAtMs, responseTimeMs);
        remember(studentId, sequence, ack);
        return ack;
    }

    /** ADR-4: the flush timer only calls this when {@link #isDirty()} -- a silent room broadcasts nothing. */
    boolean isDirty() {
        return !dirtyStudentIds.isEmpty();
    }

    /**
     * Builds and returns the next outbound broadcast, then clears dirty state. Every
     * {@value #FULL_SNAPSHOT_EVERY_N_FLUSHES}th flush sends a full snapshot instead of a delta
     * (§G2a) -- a safety net against a best-effort delta dropped under backpressure (§5.4),
     * since PH-3's client-side resync does not exist yet.
     */
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

    /**
     * §G2 D1-D4: {@code players} carries only students with a roster change since the last
     * flush, each a full {@code PlayerState} (not a field-level diff). A student absent from a
     * delta means unchanged, never removed (D3) -- leaving a room must show up as
     * {@code connected = false}, not as silence.
     */
    private GameMessage buildDeltaSnapshot() {
        RoomStateSnapshot.Builder snapshot = baseSnapshotBuilder(false);
        dirtyStudentIds.forEach(studentId -> snapshot.addPlayers(buildPlayerState(studentId)));
        return buildSnapshotMessage(snapshot);
    }

    private RoomStateSnapshot.Builder baseSnapshotBuilder(boolean full) {
        return RoomStateSnapshot.newBuilder()
                .setFull(full)
                .setPhase(phase)
                .setCurrentQuestionId(currentQuestionId == null ? "" : currentQuestionId)
                .setServerQuestionStartedAtMs(serverQuestionStartedAtMs)
                .setDeadlineMs(deadlineMs);
    }

    private GameMessage buildSnapshotMessage(RoomStateSnapshot.Builder snapshot) {
        return GameMessage.newBuilder()
                .setType(MessageType.ROOM_STATE_SNAPSHOT)
                .setRoomId(roomId)
                .setRoomStateSnapshot(snapshot)
                .build();
    }

    private PlayerState buildPlayerState(String studentId) {
        PlayerRecord record = players.get(studentId);
        return PlayerState.newBuilder()
                .setStudentId(studentId)
                .setStudentIndex(record.index)
                .setDisplayName(record.displayName)
                .setScore(totalScoreOf(studentId))
                .setAnsweredCurrent(record.answeredCurrent)
                .setConnected(record.connected)
                .build();
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

    /** Roster entry. {@code index} is assigned once at join and never reassigned (§G2 D4). */
    private static final class PlayerRecord {
        private final int index;
        private final String displayName;
        private boolean answeredCurrent;
        private boolean connected;

        PlayerRecord(int index, String displayName) {
            this.index = index;
            this.displayName = displayName;
        }
    }
}
