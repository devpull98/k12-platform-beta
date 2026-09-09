package com.uni.realtime.gameengine.definition;

/**
 * `shared_resource` (INCLASS-GAME-001-v2.1 §3.1, Group A, `cooperative` mode only): what a wrong
 * answer costs the whole room. Plain domain enum, same convention as {@link MissedStepPolicy}/
 * {@link TickMode} -- not protobuf-backed, since {@code SharedResourceState.resource_type} on the
 * wire is a plain string (see game_message.proto's comment on why).
 */
public enum SharedResourceType {
    NONE,
    TIME,
    LIVES
}
