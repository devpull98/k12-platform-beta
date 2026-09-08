package com.uni.realtime.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 verification: a message that has every field set must survive
 * serialize -> deserialize unchanged.
 *
 * <p>Populating every field matters more than it looks. A round trip over a half-empty
 * message passes even when a field was renumbered or dropped from the schema, because
 * proto3 simply omits defaults from the wire. The gateway and the engine share this one
 * artifact, so a silent schema drift here is a wire incompatibility between two services.
 */
class GameMessageRoundTripTest {

    @Test
    @DisplayName("client -> server frame survives a round trip with every field set")
    void clientBoundMessageRoundTrips() throws InvalidProtocolBufferException {
        GameMessage original = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setRoomId("room-42")
                .setStudentId("student-7")
                .setStudentIndex(3)
                .setSequence(9_001L)
                .setClientTimestampMs(1_764_000_000_000L)
                .setInternal(InternalHeader.newBuilder()
                        .setOwnerPodId("engine-1")
                        .setEpoch(17L)
                        .setTraceId("4bf92f3577b34da6a3ce929d0e0e4736")
                        .setGatewayPodId("gw-0")
                        .setRoutingStatus(RoutingStatus.OK)
                        .setDeliveryClass(DeliveryClass.CRITICAL))
                .setSubmitAnswer(SubmitAnswer.newBuilder()
                        .setQuestionId("q-3")
                        .addAllAnswerIds(List.of("a", "c"))
                        .setFreeText("vi du tra loi tu luan"))
                .build();

        GameMessage parsed = GameMessage.parseFrom(original.toByteArray());

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getType()).isEqualTo(MessageType.SUBMIT_ANSWER);
        assertThat(parsed.getRoomId()).isEqualTo("room-42");
        assertThat(parsed.getStudentId()).isEqualTo("student-7");
        assertThat(parsed.getStudentIndex()).isEqualTo(3);
        assertThat(parsed.getSequence()).isEqualTo(9_001L);
        assertThat(parsed.getClientTimestampMs()).isEqualTo(1_764_000_000_000L);
        assertThat(parsed.getSubmitAnswer().getAnswerIdsList()).containsExactly("a", "c");
    }

    @Test
    @DisplayName("InternalHeader carries owner_pod_id and epoch across the wire")
    void internalHeaderRoundTrips() throws InvalidProtocolBufferException {
        // owner_pod_id is what the gateway's route cache learns from (8.2); epoch is unused
        // in Phase 1 but must already ride the wire so Phase 2 needs no schema change.
        GameMessage original = GameMessage.newBuilder()
                .setType(MessageType.ANSWER_ACK)
                .setRoomId("room-42")
                .setInternal(InternalHeader.newBuilder()
                        .setOwnerPodId("engine-2")
                        .setEpoch(5L)
                        .setRoutingStatus(RoutingStatus.NOT_OWNER))
                .build();

        InternalHeader parsed = GameMessage.parseFrom(original.toByteArray()).getInternal();

        assertThat(parsed.getOwnerPodId()).isEqualTo("engine-2");
        assertThat(parsed.getEpoch()).isEqualTo(5L);
        assertThat(parsed.getRoutingStatus()).isEqualTo(RoutingStatus.NOT_OWNER);
    }

    @Test
    @DisplayName("every payload the phase-1 flow needs is reachable through the oneof")
    void oneofCoversThePhaseOneMessageSet() {
        assertThat(payloadCaseOf(b -> b.setJoinRoom(JoinRoom.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.JOIN_ROOM);
        assertThat(payloadCaseOf(b -> b.setSubmitAnswer(SubmitAnswer.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.SUBMIT_ANSWER);
        assertThat(payloadCaseOf(b -> b.setResync(Resync.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.RESYNC);
        assertThat(payloadCaseOf(b -> b.setTeacherCommand(TeacherCommand.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.TEACHER_COMMAND);
        assertThat(payloadCaseOf(b -> b.setRoomStateSnapshot(RoomStateSnapshot.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.ROOM_STATE_SNAPSHOT);
        assertThat(payloadCaseOf(b -> b.setAnswerAck(AnswerAck.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.ANSWER_ACK);
        assertThat(payloadCaseOf(b -> b.setQuestionStarted(QuestionStarted.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.QUESTION_STARTED);
        assertThat(payloadCaseOf(b -> b.setStudentJoined(StudentJoined.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.STUDENT_JOINED);
        assertThat(payloadCaseOf(b -> b.setGameOver(GameOver.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.GAME_OVER);
        assertThat(payloadCaseOf(b -> b.setConnectionDegraded(ConnectionDegraded.getDefaultInstance())))
                .isEqualTo(GameMessage.PayloadCase.CONNECTION_DEGRADED);
    }

    @Test
    @DisplayName("Resync nests unacked submissions so a reconnect can replay them")
    void resyncCarriesPendingSubmissions() throws InvalidProtocolBufferException {
        GameMessage pending = GameMessage.newBuilder()
                .setType(MessageType.SUBMIT_ANSWER)
                .setSequence(11L)
                .setSubmitAnswer(SubmitAnswer.newBuilder().setQuestionId("q-2"))
                .build();

        GameMessage original = GameMessage.newBuilder()
                .setType(MessageType.RESYNC)
                .setStudentId("student-7")
                .setResync(Resync.newBuilder().setLastAckedSeq(10L).addPending(pending))
                .build();

        Resync parsed = GameMessage.parseFrom(original.toByteArray()).getResync();

        assertThat(parsed.getLastAckedSeq()).isEqualTo(10L);
        assertThat(parsed.getPendingList()).containsExactly(pending);
    }

    @Test
    @DisplayName("STUDENT_LEFT and STUDENT_KICKED carry no payload -- room_id/student_id alone survive the round trip")
    void noPayloadNotificationTypesRoundTrip() throws InvalidProtocolBufferException {
        GameMessage left = GameMessage.newBuilder()
                .setType(MessageType.STUDENT_LEFT)
                .setRoomId("room-42")
                .setStudentId("student-7")
                .build();
        GameMessage parsedLeft = GameMessage.parseFrom(left.toByteArray());
        assertThat(parsedLeft.getType()).isEqualTo(MessageType.STUDENT_LEFT);
        assertThat(parsedLeft.getRoomId()).isEqualTo("room-42");
        assertThat(parsedLeft.getStudentId()).isEqualTo("student-7");
        assertThat(parsedLeft.getPayloadCase()).isEqualTo(GameMessage.PayloadCase.PAYLOAD_NOT_SET);

        GameMessage kicked = GameMessage.newBuilder()
                .setType(MessageType.STUDENT_KICKED)
                .setRoomId("room-42")
                .setStudentId("student-9")
                .build();
        GameMessage parsedKicked = GameMessage.parseFrom(kicked.toByteArray());
        assertThat(parsedKicked.getType()).isEqualTo(MessageType.STUDENT_KICKED);
        assertThat(parsedKicked.getRoomId()).isEqualTo("room-42");
        assertThat(parsedKicked.getStudentId()).isEqualTo("student-9");
        assertThat(parsedKicked.getPayloadCase()).isEqualTo(GameMessage.PayloadCase.PAYLOAD_NOT_SET);
    }

    private static GameMessage.PayloadCase payloadCaseOf(java.util.function.Consumer<GameMessage.Builder> setPayload) {
        GameMessage.Builder builder = GameMessage.newBuilder();
        setPayload.accept(builder);
        return builder.build().getPayloadCase();
    }
}
