package com.axelor.apps.aiaccountant.route;

/**
 * How much of a list the instruction covers — the structured form of «всички», «следващите две»,
 * «останалите» and «тези, които са за преглед».
 *
 * <h2>Why this is a routing value and not a word list</h2>
 *
 * The first attempt read the message itself: a closed whitelist of quantifiers, imperatives and
 * document nouns, and anything outside it refused the turn. That is a magic-phrase interface. It
 * rejected «осчетоводи фактурите», «останалите» and «следващите две» — ordinary Bulgarian for
 * exactly the three things the server CAN resolve — while teaching users to guess which synonym the
 * server happens to know. Filters cannot be enumerated either, so the whitelist could only ever
 * grow and still be wrong.
 *
 * <p>Reading natural language is the router's job and it already reads this message, with the
 * conversation and the page in front of it. So it states which of these four shapes the instruction
 * has, and the server — which still owns every record, every permission and every company boundary
 * — resolves exactly that shape and freezes it. The model interprets; the server authorises.
 * Neither half can do the other's work: a {@code COUNT} of two cannot become three, a {@code
 * FILTERED} request the server has no query for is refused rather than widened, and none of these
 * values names a record.
 *
 * <p>Absent (null on {@link AiRoute}) is a fifth, distinct state and must stay distinguishable: it
 * means nothing stated how much of the list is meant — the deterministic adapter route, used when
 * the router is off or did not answer. It is never read as {@link #WHOLE}; see {@code
 * AiWorkflowController.authoriseTargets}, which refuses it with its own code rather than guessing.
 */
public enum AiSetSelection {

  /** Every eligible record of the list's own domain — «всички», «цялата опашка», "all of them". */
  WHOLE,

  /**
   * The first {@code count} eligible records in the domain's own stable order — «следващите две»,
   * «първите три», "the next one". The order is the worklist provider's, documented and repeatable;
   * it is never "whichever the model found first".
   */
  COUNT,

  /**
   * What is left of the job already on record — «останалите», «довърши ги», "the rest". Resolved
   * from that job's own remaining targets, never from a fresh query, so it cannot pick up a record
   * that appeared after the authorisation was given.
   */
  REMAINING,

  /**
   * A named subset of the queue — «тези за преглед», "the ones that need review". Honoured only
   * when a worklist provider offers that filter by name; anything else is refused precisely, naming
   * the filter, and never silently answered with the whole queue.
   */
  FILTERED
}
