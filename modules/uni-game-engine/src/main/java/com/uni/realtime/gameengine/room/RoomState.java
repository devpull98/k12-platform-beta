package com.uni.realtime.gameengine.room;

import com.uni.realtime.gameengine.definition.MissedStepPolicy;
import com.uni.realtime.gameengine.scoring.ScoreCalculator;
import com.uni.realtime.protocol.AnswerAck;
import com.uni.realtime.protocol.CommittedSeq;
import com.uni.realtime.protocol.GameMessage;
import com.uni.realtime.protocol.GamePhase;
import com.uni.realtime.protocol.MessageType;
import com.uni.realtime.protocol.PlayerState;
import com.uni.realtime.protocol.RejectReason;
import com.uni.realtime.protocol.RoomStateSnapshot;

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
     *
     * <p>Known, unmitigated risk (Task 13 review): every room counts its OWN flushes from 0
     * independently, so rooms with correlated timing (a whole class starting the same quiz
     * together, or a server-driven question deadline everyone submits near) can end up sending
     * their full snapshots in the same ~200ms window system-wide, spiking outbound bandwidth
     * instead of smoothing it. A phase offset per room (e.g. seeded from creation time) would
     * fix this, but every candidate seed either reopens this already-decided cadence with an
     * unvalidated new parameter or risks making tests that use a real/uncontrolled {@link Clock}
     * (e.g. {@code RoomSupervisorTest}) intermittently flaky. Left as-is pending either PH-1
     * load-test evidence that this matters at target scale, or an explicit decision on how to
     * seed the jitter safely.
     */
    private static final int FULL_SNAPSHOT_EVERY_N_FLUSHES = 10;

    private final String roomId;
    private final Clock clock;
    private final ScoreCalculator scoreCalculator;
    private final MissedStepPolicy missedStepPolicy;

    private final Map<String, Long> lastSeenSequence = new HashMap<>();
    private final Map<String, GameMessage> lastAckByStudent = new HashMap<>();
    private final Map<String, Integer> totalScoreByStudent = new HashMap<>();

    /** Roster, keyed by join order via {@link PlayerRecord#index} (§3.6: index, not UUID, on the wire). */
    private final Map<String, PlayerRecord> players = new LinkedHashMap<>();
    /** Student ids with roster state changed since the last flush -- the "dirty" of ADR-4. */
    private final Set<String> dirtyStudentIds = new LinkedHashSet<>();
    private int nextStudentIndex = 0;
    private int flushesSinceFullSnapshot = 0;
    /** Task 16 / B3: monotonic per-room broadcast counter (proto field 7), never reset on restore. */
    private long broadcastSeq = 0;
    /**
     * Task 17 (B4, §4.8): how many times {@link #startQuestion} has run so far, i.e. how many
     * steps a student joining RIGHT NOW would have missed. Survives restore (§9.2 Rủi ro 4's
     * lease-handoff case) so a late joiner is still correctly counted as late on whichever pod
     * ends up owning this room, not just the one that started the game.
     */
    private int questionsStartedCount = 0;

    private GamePhase phase = GamePhase.LOBBY;
    private String currentQuestionId;
    private List<String> currentCorrectAnswerIds = List.of();
    private long serverQuestionStartedAtMs;
    private long deadlineMs;

    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator) {
        this(roomId, clock, scoreCalculator, MissedStepPolicy.ZERO);
    }

    /**
     * Task 17 (B4): additive over the three-arg constructor above so every existing caller with
     * no policy to offer (tests written before this task) keeps compiling unchanged, same shape
     * {@code RoomActor.create}'s overloads already use for {@code snapshotStore}/{@code epoch}.
     */
    RoomState(String roomId, Clock clock, ScoreCalculator scoreCalculator, MissedStepPolicy missedStepPolicy) {
        this.roomId = roomId;
        this.clock = clock;
        this.scoreCalculator = scoreCalculator;
        this.missedStepPolicy = missedStepPolicy;
    }

    /**
     * Task 14 (Hot Snapshot, §5.8): everything needed to resume this room on another pod after
     * a lease handoff, deliberately excluding {@link #dirtyStudentIds} (transient broadcast
     * state, meaningless once flushed to a byte array) and the per-student ack history (see
     * {@link #submitAnswer}). The wire format is hand-rolled rather than reusing
     * {@code RoomStateSnapshot} from {@code uni-protocol} on purpose: this is Engine-internal
     * persistence, never seen by a Gateway or client, so it must not be coupled to
     * {@code game_message.proto} -- growing this format cannot force a wire-schema PR (Rollback
     * plan's "đổi .proto phải đi qua PR riêng" is about the SHARED envelope, not this).
     */
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
        } catch (IOException e) {
            // ByteArrayOutputStream/DataOutputStream never actually throw IOException in
            // practice (no real I/O underneath) -- this is here only so the try-with-resources
            // compiles, not a reachable failure mode worth a checked exception on the API.
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /**
     * Task 14 (Hot Snapshot): rebuilds a room from bytes produced by {@link #serializeSnapshot()}
     * on some earlier instance -- possibly on a different pod, after a lease handoff. The
     * restored room starts with an empty {@link #dirtyStudentIds} (nothing to re-broadcast; a
     * reconnecting client gets a full snapshot through the ordinary join/{@code RESYNC} path,
     * not through dirty-flag replay) and an empty ack history (see the comment in
     * {@link #submitAnswer} for why {@link #lastSeenSequence} alone is sufficient for dedupe).
     *
     * <p>Throws {@link UncheckedIOException} for a payload too short/malformed for this format
     * (an {@code EOFException} is an {@code IOException}) -- the caller
     * ({@code SnapshotEnvelope}) is the one that decides a corrupt payload means "treat as empty
     * state" (§5.8) by catching it there; this method's job is only to parse bytes that already
     * passed the CRC32 check, so a throw here signals a real bug (format mismatch despite a
     * valid checksum), not an expected runtime case.
     */
    static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator, byte[] payload) {
        return restore(roomId, clock, scoreCalculator, MissedStepPolicy.ZERO, payload);
    }

    /** Task 17 (B4): additive over the four-arg {@link #restore} above, same reason as the constructor overload. */
    static RoomState restore(String roomId, Clock clock, ScoreCalculator scoreCalculator,
            MissedStepPolicy missedStepPolicy, byte[] payload) {
        RoomState state = new RoomState(roomId, clock, scoreCalculator, missedStepPolicy);
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

    /**
     * Full snapshot on join (§6.2) -- the joiner needs the whole room, not a delta built for
     * players who were already there. Rejoin with the same {@code studentId} keeps its
     * original {@code student_index}: identity in the delta wire format depends on that index
     * staying stable (§G2 D4).
     */
    GameMessage joinRoom(String studentId, String displayName) {
        // computeIfAbsent's lambda only runs for a GENUINELY new studentId -- a reconnect (§4.8:
        // "hai luồng hoàn toàn khác nhau") never re-enters it, so missedStepsAtJoin is captured
        // exactly once, at the student's real first join, and never recomputed on reconnect.
        PlayerRecord record = players.computeIfAbsent(studentId, id -> {
            int missedSteps = questionsStartedCount;
            applyMissedStepPolicy(missedSteps);
            return new PlayerRecord(nextStudentIndex++, displayName, missedSteps);
        });
        record.connected = true;
        dirtyStudentIds.add(studentId);
        return buildFullSnapshot();
    }

    /**
     * Task 17 (B4): makes {@code ZERO} the deliberate, observable outcome for a late joiner
     * instead of an accident of {@code totalScoreByStudent} defaulting a missing student to 0
     * (§9.4/{@link #totalScoreOf}) -- {@link #missedStepsFor} lets a test (or future analytics)
     * confirm this branch actually ran, not just that the score happens to be 0. A no-op for
     * {@code missedSteps == 0} (joined before the first question started -- not late at all).
     *
     * @throws IllegalStateException if {@link #missedStepPolicy} is anything but {@code ZERO} --
     *     {@code DefinitionLoader} and {@code RoomActor.create} both already reject
     *     {@code SKIP}/{@code ALLOW_LATE} before a {@code RoomState} is ever constructed (same
     *     layered fail-fast {@code tickMode} uses); reaching here with one means a caller bypassed
     *     both guards.
     */
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

    /**
     * Task 17 (B4): steps missed by this student at the moment they first joined -- 0 for a
     * student who joined before the first question started (not late). Fixed for the student's
     * whole time in the room; a reconnect never changes it (see {@link #joinRoom}). Package-private:
     * test-only today (no wire field carries this yet), but a real, intentional signal rather than
     * a debug hook -- see {@link #applyMissedStepPolicy}.
     */
    int missedStepsFor(String studentId) {
        PlayerRecord record = players.get(studentId);
        return record == null ? 0 : record.missedStepsAtJoin;
    }

    void startQuestion(String questionId, long durationMs, List<String> correctAnswerIds) {
        this.currentQuestionId = questionId;
        this.currentCorrectAnswerIds = correctAnswerIds;
        this.serverQuestionStartedAtMs = clock.millis();
        this.deadlineMs = serverQuestionStartedAtMs + durationMs;
        // Task 17 (B4): every question that has ever started is one more step a FUTURE joiner
        // would have missed -- incremented here, unconditionally, regardless of how this got
        // called (the real step-graph flow doesn't exist yet; a test/ops hook drives this today,
        // same as everywhere else in Phase 1 that starts a question).
        this.questionsStartedCount++;

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
            // No original ack to replay -- happens after this RoomState was rebuilt from a Hot
            // Snapshot (Task 14), which persists lastSeenSequence but deliberately not a full
            // ack history (§5.2's table alone is enough to dedupe; a whole ack log would not
            // fit the < 5 KB budget, §4.8). Falling through past this point would re-run
            // scoreCalculator.award(...) and double-count -- exactly the bug this table exists
            // to prevent. RejectReason.DUPLICATE_SEQUENCE was reserved in the schema for this.
            // Prove-it (2026-09-07): temporarily falling through here turned exactly this test
            // red (asserted `accepted=false`, got `true`), confirming the fix is exercised.
            return buildAck(studentId, sequence, questionId, false, RejectReason.DUPLICATE_SEQUENCE,
                    0, totalScoreOf(studentId), serverReceivedAtMs, 0);
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

    /**
     * PH-3 / §9.3: full snapshot addressed to one reconnecting student, personal delivery (same
     * convention {@link #joinRoom} uses). Has no side effect on roster/dirty state -- a resync is
     * not a new join -- and reuses {@link #buildFullSnapshot()}, so it shares the one
     * {@code broadcast_seq} counter (§B3) with every other snapshot this room ever emits.
     */
    GameMessage resyncSnapshot(String studentId) {
        return buildFullSnapshot().toBuilder().setStudentId(studentId).build();
    }

    /** ADR-4: the flush timer only calls this when {@link #isDirty()} -- a silent room broadcasts nothing. */
    boolean isDirty() {
        return !dirtyStudentIds.isEmpty();
    }

    /**
     * Task 15: an immutable copy of {@link #lastSeenSequence}, taken on {@code RoomActor}'s own
     * thread at the moment a Hot Snapshot is serialized. {@code RoomActor} holds onto this copy
     * and uses it later, from the async callback that learns whether the snapshot write
     * succeeded, to build a {@code CommittedSeq} -- that callback runs on whatever thread
     * completed the store's future, never this room's own actor thread, so it must not touch
     * {@code this} again (no synchronization exists for that, by design: only the owning actor
     * is ever supposed to read or write this instance).
     */
    Map<String, Long> lastSeenSequenceSnapshot() {
        return Map.copyOf(lastSeenSequence);
    }

    /**
     * Leave-room flow / kick: flips {@code connected} to {@code false} for a student already in
     * {@link #players} and marks it dirty so the next coalescing flush carries it (§G2 D3 --
     * leaving must show up as {@code connected = false}, never as silence). No-op for a student
     * not on the roster (e.g. a channel that disconnected before ever completing JOIN_ROOM).
     * Never removes the entry -- score/{@code student_index} history stays intact, matching the
     * "never removed" convention {@link #buildDeltaSnapshot()} already documents.
     */
    void markDisconnected(String studentId) {
        PlayerRecord record = players.get(studentId);
        if (record == null) {
            return;
        }
        record.connected = false;
        dirtyStudentIds.add(studentId);
    }

    /**
     * TeacherCommand.KICK_STUDENT's notice to the target student -- static and pure, same shape
     * as {@link #buildCommittedSeq}, so {@code RoomActor} can send it via {@code broadcastTarget}
     * (fan-out to every subscribed Gateway pod, §B1) without this class needing to know which
     * pod actually holds that student's channel.
     */
    static GameMessage buildStudentKicked(String roomId, String studentId) {
        return GameMessage.newBuilder()
                .setType(MessageType.STUDENT_KICKED)
                .setRoomId(roomId)
                .setStudentId(studentId)
                .build();
    }

    /**
     * Task 15 / B2: deliberately a static, pure function of its arguments -- not an instance
     * method -- so the async snapshot-write callback in {@code RoomActor} can build a
     * {@code CommittedSeq} from a previously captured {@link #lastSeenSequenceSnapshot()} without
     * reaching back into a {@code RoomState} instance from a foreign thread.
     */
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
        // Every RoomStateSnapshot this room ever emits (full or delta, join-triggered or
        // flush-triggered) shares this one counter -- a client needs an unbroken sequence to
        // detect a dropped broadcast (B3), not one restarted per snapshot type.
        // Prove-it (2026-09-07): freezing this (not incrementing) turned exactly the 2 tests
        // that assert monotonic broadcast_seq red -- confirms they exercise this line.
        snapshot.setBroadcastSeq(++broadcastSeq);
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
        /** Task 17 (B4): set once at construction, same lifetime rule as {@code index}. */
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
