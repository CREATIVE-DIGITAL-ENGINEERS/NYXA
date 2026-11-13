package com.axelor.apps.aiaccountant.context;

/**
 * Turns "the user has record X of model Y open" into the identifier the tools actually take.
 *
 * <h2>The problem it solves</h2>
 *
 * The OCR Inbox is a filtered <em>Invoice</em> view, so when an accountant asks a question with it
 * open, the page context says {@code com.axelor.apps.account.db.Invoice} and an invoice id. Every
 * OCR tool addresses its target by {@code uploadId}. The model therefore had exactly one way to get
 * from what it was told to what it could use: call {@code search_ocr_records}. That is a full extra
 * model request and tool round trip, on the majority of turns, to rediscover a record the server
 * already had in its hand.
 *
 * <p>A resolver closes that gap on the server, where the join is a single indexed lookup.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Implementations are discovered by {@code MetaScanner}, exactly like {@code AiTool}, so a
 *       feature module contributes one without this module depending on it;
 *   <li>{@link #describe} returns a short sentence to put in the context, or {@code null} when this
 *       resolver does not handle that model or finds nothing. Returning null must be cheap;
 *   <li>it must respect the active-company scope — the context line is model-visible, and a record
 *       from another company must not become visible through it;
 *   <li>it must not throw. A context enrichment that fails has to degrade to "no extra context",
 *       never to a broken turn.
 * </ul>
 */
public interface AiPageRecordResolver {

  /**
   * @param model the fully-qualified entity name the client reported
   * @param recordId the open record's id
   * @return a sentence for the context block, or null
   */
  String describe(String model, long recordId);

  /**
   * The record's current lifecycle state in a few words, or null when unknown.
   *
   * <p>Separate from {@link #describe} because it answers a different question and is read in a
   * different place: when a turn is cut short, the server has to tell the user what is actually in
   * the database — "Invoice 594 is validated but not posted" — rather than count the tool calls it
   * managed to make. A count of successful calls is not a statement about business state, and
   * reporting it as one is how "5 of 5 operations succeeded" ended up attached to an unfinished
   * workflow.
   *
   * <p>Must be short, must be the truth as the database has it right now, and must be as cheap and
   * as company-scoped as {@link #describe}. Default null, so existing resolvers are unaffected.
   */
  default String state(String model, long recordId) {
    return null;
  }

  /**
   * The same fact as {@link #state}, as one token the server can compare rather than print.
   *
   * <p>Prose is for the user; this is for the decision. Whether a turn that ran out of iterations
   * actually left work unfinished can only be settled by comparing what the user authorised against
   * where the record now is, and "invoice 606 is POSTED" is a sentence, not a comparison. Parsing
   * it back would make the wording load-bearing, so the wording and the token are produced
   * separately from the same lookup.
   *
   * <p>The vocabulary is closed: {@code DRAFT}, {@code VALIDATED}, {@code POSTED}, {@code
   * CANCELLED}, or null when this resolver cannot say. Anything outside it is treated as null — an
   * unrecognised token must never be read as progress.
   *
   * @see com.axelor.apps.aiaccountant.service.AiWorkflowIntent
   */
  default String lifecycle(String model, long recordId) {
    return null;
  }
}
