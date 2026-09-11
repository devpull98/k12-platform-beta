package com.uni.realtime.gameengine.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uni.realtime.protocol.GameMode;
import com.uni.realtime.protocol.TeamAssignment;

import java.io.IOException;
import java.util.List;

/**
 * Translates the CMS-facing {@link GameSessionDefinitionRequest} JSON contract to/from a real
 * {@link GameDefinition}, running every inbound definition through {@link DefinitionLoader}
 * (Task 11's guardrails) before it can ever reach a room -- a malformed/malicious definition from
 * a CMS bug must fail HERE, not surface as a RoomActor crash later. Lives in this package (not
 * with the Spring-only REST controller in {@code provisioning}) so {@code RoomSupervisor} (plain
 * Pekko, no Spring dependency) can use it directly when resolving a real join.
 */
public final class GameSessionDefinitionMapper {

    private final ObjectMapper objectMapper;
    private final DefinitionLoader definitionLoader;

    public GameSessionDefinitionMapper(ObjectMapper objectMapper, DefinitionLoader definitionLoader) {
        this.objectMapper = objectMapper;
        this.definitionLoader = definitionLoader;
    }

    /** @throws DefinitionRejectedException if {@code request} references an unknown enum value or
     * the resulting {@link GameDefinition} fails {@link DefinitionLoader}'s guardrails -- callers
     * that only need to validate (e.g. before persisting) should call this instead of round-
     * tripping through JSON via {@link #toJsonBytes}/{@link #fromJsonBytes}. */
    public GameDefinition validate(GameSessionDefinitionRequest request) throws DefinitionRejectedException {
        return definitionLoader.load(toGameDefinition(request));
    }

    public byte[] toJsonBytes(GameSessionDefinitionRequest request) throws DefinitionRejectedException {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (IOException e) {
            throw new DefinitionRejectedException("could not serialize game session definition: " + e.getMessage());
        }
    }

    /** @throws DefinitionRejectedException if the JSON is malformed, references an unknown enum
     * value, or the resulting {@link GameDefinition} fails {@link DefinitionLoader}'s guardrails. */
    public GameDefinition fromJsonBytes(byte[] jsonBytes) throws DefinitionRejectedException {
        GameSessionDefinitionRequest request;
        try {
            request = objectMapper.readValue(jsonBytes, GameSessionDefinitionRequest.class);
        } catch (IOException e) {
            throw new DefinitionRejectedException("malformed game session definition JSON: " + e.getMessage());
        }
        return definitionLoader.load(toGameDefinition(request));
    }

    private GameDefinition toGameDefinition(GameSessionDefinitionRequest request) throws DefinitionRejectedException {
        try {
            GameMode gameMode = GameMode.valueOf("GAME_MODE_" + requireNonNull(request.gameMode(), "game_mode"));
            List<Question> questions = request.questions() == null ? List.of() : request.questions().stream()
                    .map(q -> new Question(q.questionText(), q.options(), q.correctOptionIndex()))
                    .toList();
            List<ProgressStage> progressStages = request.progressStages() == null ? List.of() : request.progressStages().stream()
                    .map(s -> new ProgressStage(s.milestonePercent(), s.svgUrl()))
                    .toList();
            SharedResourceType sharedResourceType = request.sharedResourceType() == null
                    ? SharedResourceType.NONE : SharedResourceType.valueOf(request.sharedResourceType());
            List<TeamAssignment> teamRosters = request.teams() == null ? List.of() : request.teams().stream()
                    .map(t -> TeamAssignment.newBuilder()
                            .setTeamId(t.teamId())
                            .setTeamName(t.teamName())
                            .addAllStudentIds(t.studentIds())
                            .build())
                    .toList();
            ScoreAggregation scoreAggregation = request.scoreAggregation() == null
                    ? ScoreAggregation.SUM_ALL : ScoreAggregation.valueOf(request.scoreAggregation());
            WinCondition winCondition = request.winCondition() == null
                    ? WinCondition.PROGRESS_COMPLETED : WinCondition.valueOf(request.winCondition());
            LateJoinPolicy lateJoinPolicy = request.lateJoinPolicy() == null
                    ? LateJoinPolicy.ALLOW_WITH_ZERO_SCORE : LateJoinPolicy.valueOf(request.lateJoinPolicy());
            TeamAssignmentMode teamAssignmentMode = request.teamAssignmentMode() == null
                    ? TeamAssignmentMode.MANUAL : TeamAssignmentMode.valueOf(request.teamAssignmentMode());
            ProgressDisplayMode progressDisplayMode = request.progressDisplayMode() == null
                    ? ProgressDisplayMode.SIMPLE_BAR : ProgressDisplayMode.valueOf(request.progressDisplayMode());
            String introNarrative = request.introNarrative() == null ? "" : request.introNarrative();

            return new GameDefinition(gameMode, questions, orZero(request.maxPlayers()), progressStages, sharedResourceType,
                    orZero(request.sharedResourcePenalty()), teamRosters, scoreAggregation, winCondition,
                    orZero(request.roundTimeLimitSeconds()), lateJoinPolicy, teamAssignmentMode, introNarrative,
                    progressDisplayMode);
        } catch (IllegalArgumentException e) {
            // GameMode/enum valueOf() on an unknown value -- unify under DefinitionRejectedException
            // so callers (the REST controller, RoomSupervisor) handle exactly one exception type.
            throw new DefinitionRejectedException("invalid game session definition field: " + e.getMessage());
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String requireNonNull(String value, String fieldName) throws DefinitionRejectedException {
        if (value == null || value.isBlank()) {
            throw new DefinitionRejectedException(fieldName + " is required");
        }
        return value;
    }
}
