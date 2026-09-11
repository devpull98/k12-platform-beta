package com.uni.realtime.gameengine.definition;

/**
 * PO V2.2 §3.2 (Group B) {@code progress_display_mode}. Purely a client rendering hint today --
 * the engine does not change what it broadcasts based on this value (still always sends
 * {@code stage_index} via {@link ProgressStage}); it only needs to be carried through
 * {@link GameDefinition} until a client contract exists to consume it.
 */
public enum ProgressDisplayMode {
    SIMPLE_BAR,
    STAGED_VISUAL
}
