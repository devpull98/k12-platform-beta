package com.uni.realtime.gameengine.domain.game;

import com.uni.realtime.gameengine.definition.ScoreAggregation;
import com.uni.realtime.gameengine.definition.SharedResourceType;
import com.uni.realtime.gameengine.definition.WinCondition;
import com.uni.realtime.protocol.TeamAssignment;

import java.util.List;

public interface GameRuleContext {
    int roomProgress();
    int progressTarget();
    SharedResourceType sharedResourceType();
    int sharedResourcePenalty();
    WinCondition winCondition();
    List<TeamAssignment> teamRosters();
    ScoreAggregation scoreAggregation();

    String teamIdOf(String studentId);
    int totalScoreOf(String studentId);
    int computeTeamScore(TeamAssignment roster);

    int incrementRoomProgress();
    int incrementTeamProgress(String teamId);
    void reduceDeadlineMs(long penaltyMs);
    void setGameOver(String reason, String winnerId);
}
