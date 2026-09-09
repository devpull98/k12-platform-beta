package com.uni.realtime.gameengine.definition;

import java.util.List;

public record Step(String id, long durationMs, List<String> nextStepIds) {}
