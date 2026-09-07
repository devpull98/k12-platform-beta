package com.uni.realtime.engine.net;

import com.uni.realtime.protocol.DeliveryClass;
import com.uni.realtime.protocol.MessageType;

/**
 * §5.4's Critical/Best-effort table, as code. The proto comment on {@code InternalHeader
 * .delivery_class} says the gateway should not have to re-derive this from {@code type} --
 * which means somewhere on the Engine side must compute it once, here, and stamp it before a
 * message leaves this pod (see {@code RoomSupervisor}/{@code ChannelReplyActor}, Task 13).
 */
public final class DeliveryClassifier {

    private DeliveryClassifier() {}

    public static DeliveryClass classify(MessageType type) {
        return switch (type) {
            case ANSWER_ACK, GAME_OVER, TEACHER_COMMAND, QUESTION_STARTED, CONNECTION_DEGRADED ->
                    DeliveryClass.CRITICAL;
            default -> DeliveryClass.BEST_EFFORT;
        };
    }
}
