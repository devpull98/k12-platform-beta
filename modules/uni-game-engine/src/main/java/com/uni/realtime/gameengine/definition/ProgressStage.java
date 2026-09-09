package com.uni.realtime.gameengine.definition;

/**
 * One entry of `progress_stages` (INCLASS-GAME-001-v2.1 §3.2, Group B): a percentage milestone
 * and the SVG the client should switch to once room progress reaches it. Purely descriptive data
 * for the client's {@code staged_visual} rendering -- the engine only ever compares
 * {@code milestonePercent} against the computed percentage to pick a {@code stage_index}
 * (see {@code RoomState}), it never reads {@code svgUrl}.
 */
public record ProgressStage(int milestonePercent, String svgUrl) {}
