package com.axelor.apps.aiaccountant.service;

import java.util.Locale;

/**
 * What kind of obstacle a refusal is, and therefore what should happen next.
 *
 * <h2>Why a refusal needed a kind</h2>
 *
 * Every tool refusal arrived as the same {@code {"error": …}} envelope, so every refusal looked
 * alike: a missing expense account the assistant could resolve itself, and a duplicate invoice
 * number only a human may wave through, were the same shape on the wire. The model treated them the
 * same way it treats anything it cannot tell apart — it stopped and asked — and a bulk job that hit
 * one fixable blocker on one record turned into a question instead of the four postings it was
 * asked for.
 *
 * <p>The classification is not accounting reasoning and must not become any. It reads the code and
 * message a tool already returned and answers one orchestration question: does the assistant keep
 * working, ask a person, tell the truth about a missing capability, or report a fault. What to
 * actually repair, and how, stays entirely with the model and the tools.
 *
 * <h2>Failing toward asking</h2>
 *
 * An unrecognised refusal classifies as {@link #HUMAN_DECISION_REQUIRED}. That is the conservative
 * direction: the cost of asking about something the assistant could have fixed is one round trip,
 * and the cost of grinding away at a blocker that needed a person is a loop that spends the whole
 * turn budget and still ends in a question.
 */
public enum AiBlockerClass {

  /**
   * The assistant can clear this itself with tools it already has, under authorisation it already
   * holds. A missing expense account, a line with no tax code, invoice terms that need
   * regenerating, a payable account whose flags are off.
   */
  FIXABLE,

  /**
   * A person has to decide, and no amount of further work changes that. A duplicate supplier
   * invoice number, a PFP acknowledgement, an ambiguous supplier match, a settlement Nyxa does not
   * know, a value that is on no document and in no record.
   */
  HUMAN_DECISION_REQUIRED,

  /** Nothing here can do it. The honest answer is to say so and stop, not to keep trying. */
  CAPABILITY_MISSING,

  /**
   * Something broke rather than refused: a serialisation failure, an unexpected exception, a
   * transport problem. Distinct from a refusal because the record's state is unchanged and the same
   * call may simply work — though never blindly, and never in the same turn against the same state.
   *
   * <p>Note the premise: <em>the record's state is unchanged</em>. That is what makes a technical
   * failure safe to describe as "nothing happened", and it is exactly the premise that fails for a
   * write whose answer was lost — which is why that has a class of its own below rather than
   * borrowing this one.
   */
  TECHNICAL_FAILURE,

  /**
   * The operation may or may not have taken effect, and nothing here can tell which.
   *
   * <p>The class that did not exist, and whose absence was a real hazard: a write that timed out
   * used to arrive as {@link #TECHNICAL_FAILURE}, which is defined as "the record is unchanged and
   * the same call may simply work" — a statement nobody was in a position to make. Acting on it
   * meant either retrying a ventilation that had already posted, or telling an accountant an
   * invoice was not posted when it was.
   *
   * <p>It needs a person for a different reason than {@link #HUMAN_DECISION_REQUIRED} does. There
   * the facts are known and a judgement is missing; here a fact is missing, and the person's job is
   * to look at the record rather than to decide anything. Both stop the assistant, and only this
   * one means "I do not know what I did".
   */
  UNCERTAIN_OUTCOME;

  /** True when the assistant should carry on working rather than turn to the user. */
  public boolean shouldContinue() {
    return this == FIXABLE;
  }

  /** True when, and only when, a question to the user is the right next move. */
  public boolean needsUser() {
    return this == HUMAN_DECISION_REQUIRED || this == UNCERTAIN_OUTCOME;
  }

  /** True when nothing may be asserted about what this operation did. */
  public boolean isUncertain() {
    return this == UNCERTAIN_OUTCOME;
  }

  /**
   * Classifies one refusal from the structured fields a tool returned.
   *
   * <h2>Order of authority</h2>
   *
   * <ol>
   *   <li>The {@code blocker} detail — a business rule naming itself. This is the intended path.
   *   <li>The {@code error} envelope code — the dispatcher naming its own refusal.
   *   <li>The message, {@link #fromMessage(String) matched as prose} — the fallback, for a refusal
   *       that predates the vocabulary or comes from somewhere that does not set a code.
   * </ol>
   *
   * <p>Prose is last and is meant to stay last. Every refusal that reaches it is one that could be
   * given a constant in {@link AiBlockerCode} and has not been.
   *
   * @param blocker the {@code blocker} detail a tool set, or null
   * @param code the {@code error} field, e.g. {@code tool_error}, {@code unknown_tool}
   * @param message the {@code message} field; may be null
   */
  public static AiBlockerClass of(String blocker, String code, String message) {
    AiBlockerCode named = AiBlockerCode.parse(blocker);
    if (named != null) {
      return named.classification();
    }
    AiBlockerCode envelope = AiBlockerCode.parse(code);
    if (envelope != null) {
      return envelope.classification();
    }
    return fromMessage(message);
  }

  /**
   * Classifies from the envelope code and message alone.
   *
   * <p>Kept for callers that never see a {@code blocker} detail. {@code tool_error} is deliberately
   * not a constant in {@link AiBlockerCode}: it is the general-purpose wrapper every business rule
   * comes back through, so it says nothing on its own and falls through to the message.
   */
  public static AiBlockerClass of(String code, String message) {
    return of(null, code, message);
  }

  /**
   * The fallback: business refusals recognised by the words they happen to use.
   *
   * <h2>Why this is a fallback and not a design</h2>
   *
   * These strings are written for an accountant, in two languages, and they conjugate — so the
   * matching is on stems, and the order matters because a message that mentions both a duplicate
   * number and a missing account would otherwise be read as something to grind on. That is a lot of
   * care spent on a signal that changes whenever somebody improves a sentence. It exists so an
   * uncoded refusal still classifies rather than defaulting blindly, not because reading prose is a
   * good way to decide what to do next.
   *
   * <p>The right fix for anything that lands here is a constant in {@link AiBlockerCode} and a
   * {@code blocker} detail at the throw site.
   */
  static AiBlockerClass fromMessage(String message) {
    if (message == null || message.trim().isEmpty()) {
      return HUMAN_DECISION_REQUIRED;
    }
    String m = message.toLowerCase(Locale.ROOT);

    // Someone has to say yes, or supply a fact that exists nowhere in the system.
    if (contains(m, "duplicate", "дублира", "acknowledgeduplicate", "pfp", "same supplier invoice")
        || contains(m, "ambiguous", "нееднозначн", "several partners", "повече от един")
        || contains(m, "not recognised", "не е разпозна", "unknown settlement", "няма такова насел")
        || contains(m, "confirm", "потвърд")) {
      return HUMAN_DECISION_REQUIRED;
    }

    // The assistant has a tool for each of these and the authorisation to use it.
    if (contains(m, "account", "сметк")
        || contains(m, "tax", "данъ", "ддс")
        || contains(m, "product", "продукт", "артикул")
        || contains(m, "invoice terms", "payment terms", "падеж", "услови")
        || contains(m, "readiness", "not usable", "blocker", "блокир")
        || contains(m, "reconcile", "amounts do not", "не съвпада")) {
      return FIXABLE;
    }
    return HUMAN_DECISION_REQUIRED;
  }

  private static boolean contains(String haystack, String... needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
