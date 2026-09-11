package com.uni.realtime.gameengine.definition;

/** JSON wire shape for one {@link ProgressStage}. See {@link GameSessionDefinitionMapper}. */
public record ProgressStageRequest(int milestonePercent, String svgUrl) {}
