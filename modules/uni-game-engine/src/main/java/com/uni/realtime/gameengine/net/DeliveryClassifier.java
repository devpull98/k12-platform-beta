package com.uni.realtime.gameengine.net;

import com.uni.realtime.protocol.DeliveryClass;
import com.uni.realtime.protocol.MessageType;

public final class DeliveryClassifier {

    private DeliveryClassifier() {}

    public static DeliveryClass classify(MessageType type) {
        return switch (type) {
            case ANSWER_ACK, GAME_OVER, TEACHER_COMMAND, QUESTION_STARTED, CONNECTION_DEGRADED, STUDENT_KICKED ->
                    DeliveryClass.CRITICAL;
            default -> DeliveryClass.BEST_EFFORT;
        };
    }
}
