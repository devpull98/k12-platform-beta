package com.uni.realtime.gameengine.room;

final class PlayerRecord {
    final int index;
    final String displayName;
    final int missedStepsAtJoin;
    boolean answeredCurrent;
    boolean correctCurrent;
    boolean connected;

    PlayerRecord(int index, String displayName, int missedStepsAtJoin) {
        this.index = index;
        this.displayName = displayName;
        this.missedStepsAtJoin = missedStepsAtJoin;
    }
}
