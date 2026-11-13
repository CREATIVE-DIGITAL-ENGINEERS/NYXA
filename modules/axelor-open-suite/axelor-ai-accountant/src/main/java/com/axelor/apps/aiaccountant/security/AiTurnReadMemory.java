package com.axelor.apps.aiaccountant.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which read calls have already been answered in the current chat turn, and with what, so the same
 * question is not asked of the database twice in one breath.
 *
 * <h2>Why</h2>
 *
 * <p>A single turn routinely spent its whole tool budget re-reading: {@code search_ocr_records} to
 * find a record the user already had open, then {@code get_ocr_record} for it, then {@code
 * get_ocr_record} again a step later because the intervening reasoning had pushed the first result
 * out of view. Every one of those costs a round trip, and the second call returns byte-identical
 * data — there is nothing to learn from it.
 *
 * <p>So an identical READ (same tool, same arguments) inside one turn is answered from the result
 * this memory kept, without running the tool again. It used to be answered with an {@code
 * already_answered} error envelope instead, carrying no data: the model read a refusal where there
 * had been none, and — having asked again precisely because it no longer had the data in view — was
 * left without the answer. The earlier result is now returned as the success it was.
 *
 * <h2>Boundaries</h2>
 *
 * <p>Thread-local and turn-scoped, like {@link AiWriteEffects}: one chat request is one thread,
 * {@link #begin()} starts every turn empty and {@link #end()} releases it, so nothing crosses a
 * turn and two users' turns never see each other's calls. Within the turn each answer is also keyed
 * by the user and the company it was read under, so a company switch mid-turn can never be answered
 * with the data read for the previous one.
 *
 * <h2>What resets it</h2>
 *
 * <p>{@link #invalidate()} is called when a write commits. That is the one thing that makes a
 * repeat read genuinely informative: after a write tool succeeds, the record really is different
 * and re-reading it is exactly right. Everything else in a turn is pure reading, so the answer
 * cannot have changed.
 *
 * <p>Not a control. It saves a duplicate query; it never decides whether a caller may read.
 */
public final class AiTurnReadMemory {

  /** Ceiling, so a long turn cannot grow this without bound. */
  private static final int MAX_CALLS = 40;

  private static final ThreadLocal<Map<String, Object>> ANSWERED = new ThreadLocal<>();

  private AiTurnReadMemory() {}

  /** Opens collection for a turn, discarding anything left over. */
  public static void begin() {
    ANSWERED.set(new LinkedHashMap<>());
  }

  /** Ends collection and releases the thread-local. */
  public static void end() {
    ANSWERED.remove();
  }

  /**
   * Forgets every answered read, because the data behind them may now be different.
   *
   * <p>Called after a write commits — never on a rolled-back rehearsal or a failed apply, where
   * nothing changed and the earlier answers are still the truth.
   */
  public static void invalidate() {
    Map<String, Object> answers = ANSWERED.get();
    if (answers != null) {
      answers.clear();
    }
  }

  /**
   * True when this exact read, by this user in this company, has already been answered in this turn
   * and nothing has been written since.
   *
   * <p>Always false outside a chat turn, so the OCR intake pipeline — which has no turn — is
   * unaffected.
   */
  public static boolean alreadyAnswered(
      Long userId, Long companyId, String toolName, String argumentsJson) {
    Map<String, Object> answers = ANSWERED.get();
    return answers != null && answers.containsKey(key(userId, companyId, toolName, argumentsJson));
  }

  /**
   * The data the earlier identical read returned; only meaningful after {@link #alreadyAnswered}.
   */
  public static Object earlierAnswer(
      Long userId, Long companyId, String toolName, String argumentsJson) {
    Map<String, Object> answers = ANSWERED.get();
    return answers == null ? null : answers.get(key(userId, companyId, toolName, argumentsJson));
  }

  /**
   * Notes that this read has been answered, and with what. Only successful reads should be
   * remembered; the data is the tool's own result object, held for this turn only.
   */
  public static void remember(
      Long userId, Long companyId, String toolName, String argumentsJson, Object data) {
    Map<String, Object> answers = ANSWERED.get();
    if (answers == null || toolName == null || answers.size() >= MAX_CALLS) {
      return;
    }
    answers.put(key(userId, companyId, toolName, argumentsJson), data);
  }

  private static String key(Long userId, Long companyId, String toolName, String argumentsJson) {
    // Normalised only by trimming: two spellings of the same JSON are rare from a model, and
    // treating a different byte sequence as a new call errs towards answering rather than
    // withholding.
    return userId
        + "|"
        + companyId
        + "|"
        + toolName
        + "|"
        + (argumentsJson == null ? "" : argumentsJson.trim());
  }
}
