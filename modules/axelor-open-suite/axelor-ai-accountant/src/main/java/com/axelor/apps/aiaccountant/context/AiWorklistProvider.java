package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.route.AiDomain;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Names the records "the next one" should resolve to, from current ERP state.
 *
 * <h2>Why this exists</h2>
 *
 * «следващата фактура» was being answered from conversation history, which meant it could land on
 * an invoice that had already been posted three turns earlier — recency in a transcript is not
 * eligibility in a ledger. The model has no way to know an invoice was ventilated since it was last
 * mentioned, and no reason to doubt its own memory.
 *
 * <p>So the eligible set is computed in Java, from the database, on every turn, and stated in the
 * context block. The model then has a current answer in front of it and never has to infer one.
 *
 * <p>Company-level rather than record-level, which is what separates this from {@link
 * AiPageRecordResolver}: it answers "what is there to work on", not "what is this".
 *
 * <p>Discovered by {@code MetaScanner}, like every other extension point here, so a feature module
 * contributes one by implementing the interface. Implementations must be cheap — this runs on every
 * turn — must confine themselves to the active company, and must never throw.
 */
public interface AiWorklistProvider {

  /**
   * One short line naming the eligible records, or null when there is nothing to say.
   *
   * <p>Bounded by the implementation: a handful of records, ids only. It is a pointer into the ERP,
   * not a data dump — the model still reads each record with a tool before acting on it.
   */
  String describe();

  /**
   * The eligible records themselves, as short refs, for an instruction that named a set.
   *
   * <h2>Why this is not {@link #describe}</h2>
   *
   * That one produces a sentence for the model, capped at a handful of ids and explicitly labelled
   * a preview. This produces the <em>authorisation</em>: when a user says «осчетоводи всичко от
   * инбокса», these refs are frozen into the job's target set before any tool runs, and nothing
   * afterwards may add to it. A preview and an authorisation must not be the same list — reading a
   * capped preview as the target set is exactly how "all the remaining ones" was once carried out
   * on five of eight.
   *
   * <p>Contract: company-scoped, ordered deterministically, and at most {@code limit} entries. A
   * provider that has more than that must return {@code limit} of them and no more; the caller
   * treats a full list as "too many to authorise" and refuses rather than silently working on a
   * prefix. Never null, never throws.
   *
   * @param domain the kind of document the instruction is about
   * @param limit the most refs worth returning — one more than the caller will accept
   */
  default List<String> candidates(AiDomain domain, int limit) {
    return Collections.emptyList();
  }

  /**
   * The subsets of this domain's queue this provider can resolve by name, upper case.
   *
   * <h2>Why a filter must be declared rather than attempted</h2>
   *
   * «Осчетоводи тези, които са за преглед» names a subset the server may well be able to compute,
   * and «осчетоводи фактурите на Лидл» names one it cannot. Both arrive as a filter name from the
   * router, and the difference has to be decidable <em>before</em> any record is authorised — a
   * filter nobody implements must refuse the instruction, not quietly return the whole queue, which
   * is how a request for four invoices becomes an authorisation over forty.
   *
   * <p>So a provider states what it supports, by name, and {@code AiWorklistRegistry} answers "no"
   * for everything else. Names are the server's own vocabulary and travel to the router in the
   * contract; they are never read out of the user's words.
   *
   * @return the supported filter names, or empty — the default — when this provider has none
   */
  default Set<String> filters(AiDomain domain) {
    return Collections.emptySet();
  }

  /**
   * The eligible records of this domain that match one of {@link #filters}, as short refs.
   *
   * <p>Same contract as {@link #candidates(AiDomain, int)} in every other respect: company-scoped,
   * deterministically ordered, at most {@code limit} entries, never null, never throws. A filter
   * this provider did not declare must return empty — the registry refuses the instruction before
   * it gets here, and answering one anyway would make the declaration meaningless.
   */
  default List<String> candidates(AiDomain domain, String filter, int limit) {
    return Collections.emptyList();
  }
}
