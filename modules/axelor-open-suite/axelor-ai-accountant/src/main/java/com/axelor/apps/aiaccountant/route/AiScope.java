package com.axelor.apps.aiaccountant.route;

/**
 * Which records a turn applies to, as a kind rather than as a list.
 *
 * <p>The records themselves live in {@code AiWorkflow.targets}; this says how they were chosen,
 * which is the part that decides whether asking the user "which record?" is a reasonable question
 * or one the server could already have answered.
 *
 * <p>Closed vocabulary, for the same reason as {@link AiDomain}: it is compared, not printed.
 */
public enum AiScope {

  /** The record the user has open on screen. */
  OPEN_RECORD,

  /**
   * Records the instruction itself named, in unmistakable database-id syntax.
   *
   * <p>Produced by {@link AiExplicitReferenceParser} from the user's own message — {@code
   * invoiceId=641}, {@code Invoice#641} — and by nothing else. A bare number is not a reference,
   * and neither is «фактура №641», which names the printed document number rather than the row.
   *
   * <p>It was once reached from the other end — an {@link #UNSCOPED} turn was promoted to this
   * after its tool calls named records. That remains withdrawn, and is the distinction that matters
   * here. Tool arguments are written by the model, so treating them as evidence of what the user
   * named lets a turn authorise itself; the ids look identical whether the person supplied them or
   * the assistant chose them. This scope reads the message, once, before any tool runs, and {@code
   * AiWorkflowController} then validates and freezes the set against the same registry every other
   * scope goes through.
   */
  NAMED_RECORDS,

  /** A list view is open and no record is selected — the set is whatever that list holds. */
  WORKLIST,

  /**
   * The rows the user selected in the grid.
   *
   * <p>The selection itself, reported by the client — not the phrase «вземи някой», which named no
   * records and was briefly answered with the worklist. That substitution turned a request about
   * particular highlighted rows into one about everything eligible. A turn whose words hand over
   * the choice but whose page carries no selection is scoped by the page, exactly as it would have
   * been without the phrase.
   */
  DELEGATED_SELECTION,

  /** The targets already on the active workflow — what «продължи» refers to. */
  CONVERSATION,

  /** Nothing establishes a set. A question with no subject, or a first message with no page. */
  UNSCOPED
}
