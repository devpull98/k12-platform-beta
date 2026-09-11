package com.uni.realtime.gameengine.definition;

import java.util.List;

/** JSON wire shape for one {@link Question}, matching PO V2.2 §3.1 Group A ("question_text",
 * "options" A-D, "correct_option_index"). See {@link GameSessionDefinitionMapper}. */
public record QuestionRequest(String questionText, List<String> options, int correctOptionIndex) {}
