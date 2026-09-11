package com.uni.realtime.gameengine.definition;

import java.util.List;

/** JSON wire shape for one {@link com.uni.realtime.protocol.TeamAssignment} roster
 * (GAME_MODE_TEAM only). {@code studentIds} is the fixed roster CMS/upstream decides -- the
 * engine never invents a random assignment algorithm, see {@code GameDefinition.teamRosters}'
 * existing javadoc. See {@link GameSessionDefinitionMapper}. */
public record TeamRequest(String teamId, String teamName, List<String> studentIds) {}
