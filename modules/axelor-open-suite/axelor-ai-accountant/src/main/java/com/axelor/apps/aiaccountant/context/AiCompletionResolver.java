package com.axelor.apps.aiaccountant.context;

/**
 * Says whether the change a job asked for has actually been made to a record.
 *
 * <h2>Why a successful write is not an answer</h2>
 *
 * A job with a destination is easy to settle: the invoice is POSTED or it is not, and the ledger
 * says which. A repair has no destination — a repaired invoice is still a DRAFT — so the first
 * version settled it from the tool ledger: a write succeeded on the record, so the record was done.
 *
 * <p>That is a statement about a tool call, not about a document. A successful {@code
 * set_invoice_line_product} on one of four broken lines succeeds exactly as loudly as the one that
 * fixes the last of them. Reading it as completion means the assistant reports a job finished while
 * the record still cannot be validated — the same class of false claim the whole workflow layer was
 * built to stop, arriving through the one door left open.
 *
 * <p>So completion is asked of the domain, which is the only thing that can answer it: is this
 * record now in the state the instruction asked for, with no blocker of the kind this job could
 * have cleared still outstanding? For a supplier invoice that is its readiness; for another
 * document it will be something else, and that module will say so.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Discovered by {@code MetaScanner}, like the other extension points here.
 *   <li>{@link #isComplete} answers {@code TRUE} only when the outcome is <b>verified</b> from ERP
 *       state — never from a tool having run. {@code FALSE} when it can see that work remains.
 *       {@code null} when this resolver does not own the reference or the task.
 *   <li>No resolver, or nothing but nulls, means the target stays active. An unverifiable job is
 *       not a finished one, and claiming otherwise is precisely the failure this exists to prevent.
 *   <li>Company-scoped, cheap, and it must not throw.
 * </ul>
 */
public interface AiCompletionResolver {

  /**
   * @param ref the target, as a short reference such as {@code Upload#946}
   * @param task the {@link com.axelor.apps.aiaccountant.prompt.AiTask} name the job was opened
   *     under, which is what says <em>which</em> outcome was asked for
   * @return TRUE when verified complete, FALSE when work verifiably remains, null when this
   *     resolver cannot say
   */
  Boolean isComplete(String ref, String task);
}
