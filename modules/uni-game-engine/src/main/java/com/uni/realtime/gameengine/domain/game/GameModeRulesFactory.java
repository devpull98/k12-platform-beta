package com.uni.realtime.gameengine.domain.game;

import com.uni.realtime.protocol.GameMode;

public final class GameModeRulesFactory {

    private GameModeRulesFactory() {}

    public static GameModeRules forMode(GameMode mode) {
        if (mode == null) {
            return new SoloModeRules();
        }
        return switch (mode) {
            case GAME_MODE_COOPERATIVE -> new CooperativeModeRules();
            case GAME_MODE_TEAM -> new TeamModeRules();
            case GAME_MODE_INDIVIDUAL -> new IndividualModeRules();
            default -> new SoloModeRules();
        };
    }
}
