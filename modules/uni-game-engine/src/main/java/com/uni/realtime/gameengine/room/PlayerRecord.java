package com.uni.realtime.gameengine.room;

final class PlayerRecord {
    final int index;
    final String displayName;
    final int missedStepsAtJoin;
    boolean answeredCurrent;
    boolean correctCurrent;
    boolean connected;
    // PO V2.2 SS6 rule 4 (P2 Task 32): NEVER reset across questions (unlike answeredCurrent) --
    // a student who answered at least one question anywhere in the game still gets trophies at
    // GameOver even if they later disconnect/quit.
    boolean hasEverAnswered;

    PlayerRecord(int index, String displayName, int missedStepsAtJoin) {
        this.index = index;
        this.displayName = displayName;
        this.missedStepsAtJoin = missedStepsAtJoin;
    }
}
